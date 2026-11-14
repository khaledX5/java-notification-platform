package io.github.khaledx5.notifications.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.khaledx5.notifications.api.application.EventPublishingException;
import io.github.khaledx5.notifications.api.application.NotificationEventPublisher;
import io.github.khaledx5.notifications.api.application.Outbox;
import io.github.khaledx5.notifications.api.application.OutboxEvent;
import io.github.khaledx5.notifications.events.NotificationRequested;
import io.github.khaledx5.notifications.events.Topics;
import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the outbox is for, against a real MongoDB and a real broker: a notification survives a broker
 * outage and a relay that stops half-way, and its event still goes out.
 *
 * <p>A claim lasts one second here instead of thirty, so a failed publish is tried again quickly.
 *
 * <p>The failures are staged by two small decorators around the real outbox and the real publisher,
 * not by stubbing Mockito spies: the relay calls those beans from its scheduler thread all the time,
 * and a stub that is being set up on one thread while another thread calls the same spy can end up
 * on the wrong method.
 */
@SpringBootTest(properties = {"notifications.outbox.claim-duration=1s", "notifications.outbox.poll-interval=100ms"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, OutboxRecoveryIT.TestDoubles.class})
class OutboxRecoveryIT {

    private static final String NOTIFICATIONS = "/api/v1/notifications";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private MongoTemplate mongo;

    @Autowired
    private PublishedEvents published;

    @Autowired
    private JsonMapper json;

    @Autowired
    private UnreliableBroker broker;

    @Autowired
    private ForgetfulOutbox outbox;

    @Test
    void notificationIsAcceptedWhileTheBrokerIsDownAndPublishedOnceItIsBack() {
        String recipient = uniqueRecipient();
        broker.goDownFor(recipient);

        UUID id = accept(recipient);

        // The request succeeded although nothing could be published, and the event is waiting.
        await().atMost(TIMEOUT).until(() -> broker.attemptsFor(recipient) > 0);
        assertThat(outboxEntry(id)).doesNotContainKey("publishedAt");
        assertThat(published.of(id)).isEmpty();
        assertThat(statusOf(id)).isEqualTo("QUEUED");

        broker.comeBackFor(recipient);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(published.of(id)).hasSize(1));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(outboxEntry(id)).containsKey("publishedAt"));
        assertThat(outboxEntry(id).getInteger("attempts")).isGreaterThan(1);
        assertThat(published.of(id).getFirst().eventId())
                .hasToString(outboxEntry(id).getString("_id"));
    }

    @Test
    void outageOfOneEventDoesNotLoseTheOnesAcceptedDuringIt() {
        String unlucky = uniqueRecipient();
        broker.failFor(unlucky, 2);

        UUID first = accept(unlucky);
        UUID second = accept(uniqueRecipient());
        UUID third = accept(uniqueRecipient());

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(published.of(first)).hasSize(1);
            assertThat(published.of(second)).hasSize(1);
            assertThat(published.of(third)).hasSize(1);
        });
        assertThat(broker.attemptsFor(unlucky)).isGreaterThanOrEqualTo(3);
    }

    @Test
    void eventThatWasPublishedButNotMarkedGoesOutAgainWithTheSameEventId() {
        String recipient = uniqueRecipient();
        // Stands for a relay that stopped right after the broker confirmed the event of this recipient.
        outbox.forgetNextPublishedMarkFor(recipient);

        UUID id = accept(recipient);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(published.of(id)).hasSize(2));
        assertThat(published.of(id))
                .extracting(NotificationRequested::eventId)
                .containsOnly(published.of(id).getFirst().eventId());
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(outboxEntry(id)).containsKey("publishedAt"));
    }

    private UUID accept(String recipient) {
        MvcTestResult created = mvc.post()
                .uri(NOTIFICATIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"recipient": "%s", "channel": "EMAIL", "subject": "Order shipped", "body": "On its way"}
                        """.formatted(recipient))
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(created).bodyJson().extractingPath("$.status").isEqualTo("QUEUED");
        try {
            return UUID.fromString(json.readTree(created.getResponse().getContentAsString())
                    .get("id")
                    .asString());
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String statusOf(UUID id) {
        return mongo.getCollection("notifications")
                .find(new Document("_id", id.toString()))
                .first()
                .getString("status");
    }

    private Document outboxEntry(UUID notificationId) {
        return mongo.getCollection("outbox")
                .find(new Document("notificationId", notificationId.toString()))
                .first();
    }

    private static String uniqueRecipient() {
        return "dina-" + UUID.randomUUID() + "@example.com";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestDoubles {

        @Bean
        PublishedEvents publishedEvents() {
            return new PublishedEvents();
        }

        @Bean
        @Primary
        UnreliableBroker unreliableBroker(
                @Qualifier("kafkaNotificationEventPublisher") NotificationEventPublisher kafkaPublisher) {
            return new UnreliableBroker(kafkaPublisher);
        }

        @Bean
        @Primary
        ForgetfulOutbox forgetfulOutbox(@Qualifier("mongoOutbox") Outbox mongoOutbox) {
            return new ForgetfulOutbox(mongoOutbox);
        }
    }

    /**
     * The real publisher, except that the broker can be made to refuse the events of chosen
     * recipients. Events of every other recipient go through, so the tests do not disturb each other.
     */
    static class UnreliableBroker implements NotificationEventPublisher {

        private static final int UNTIL_IT_COMES_BACK = Integer.MAX_VALUE;

        private final NotificationEventPublisher broker;
        private final Map<String, AtomicInteger> failuresLeft = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

        UnreliableBroker(NotificationEventPublisher broker) {
            this.broker = broker;
        }

        void failFor(String recipient, int times) {
            failuresLeft.put(recipient, new AtomicInteger(times));
        }

        void goDownFor(String recipient) {
            failFor(recipient, UNTIL_IT_COMES_BACK);
        }

        void comeBackFor(String recipient) {
            failuresLeft.remove(recipient);
        }

        int attemptsFor(String recipient) {
            AtomicInteger count = attempts.get(recipient);
            return count == null ? 0 : count.get();
        }

        @Override
        public void publishRequested(OutboxEvent event) {
            attempts.computeIfAbsent(event.recipient(), recipient -> new AtomicInteger())
                    .incrementAndGet();
            AtomicInteger left = failuresLeft.get(event.recipient());
            if (left != null && left.getAndUpdate(count -> Math.max(count - 1, 0)) > 0) {
                throw new EventPublishingException("the broker is down", new IllegalStateException());
            }
            broker.publishRequested(event);
        }
    }

    /**
     * The real outbox, except that it can skip marking the event of a chosen recipient as published,
     * once, as a relay that stopped right after the broker confirmed the event would.
     */
    static class ForgetfulOutbox implements Outbox {

        private final Outbox outbox;
        private final Set<String> forgetFor = ConcurrentHashMap.newKeySet();
        private final Map<UUID, String> recipientsByEvent = new ConcurrentHashMap<>();

        ForgetfulOutbox(Outbox outbox) {
            this.outbox = outbox;
        }

        void forgetNextPublishedMarkFor(String recipient) {
            forgetFor.add(recipient);
        }

        @Override
        public void add(OutboxEvent event) {
            outbox.add(event);
        }

        @Override
        public Optional<OutboxEvent> claimNext(Instant now, Duration claimDuration) {
            Optional<OutboxEvent> next = outbox.claimNext(now, claimDuration);
            next.ifPresent(event -> recipientsByEvent.put(event.id(), event.recipient()));
            return next;
        }

        @Override
        public void markPublished(UUID eventId, Instant now) {
            String recipient = recipientsByEvent.get(eventId);
            if (recipient != null && forgetFor.remove(recipient)) {
                return;
            }
            outbox.markPublished(eventId, now);
        }

        @Override
        public long countWaiting() {
            return outbox.countWaiting();
        }
    }

    /** Plays the part of the delivery worker: keeps every request event that reaches the broker. */
    static class PublishedEvents {

        private final Map<UUID, List<NotificationRequested>> byNotification = new ConcurrentHashMap<>();

        @KafkaListener(
                topics = Topics.NOTIFICATIONS_REQUESTED,
                groupId = "outbox-recovery-it",
                properties =
                        "spring.json.value.default.type=io.github.khaledx5.notifications.events.NotificationRequested")
        void onRequested(NotificationRequested event) {
            byNotification
                    .computeIfAbsent(event.notificationId(), id -> new CopyOnWriteArrayList<>())
                    .add(event);
        }

        List<NotificationRequested> of(UUID notificationId) {
            return byNotification.getOrDefault(notificationId, List.of());
        }
    }
}
