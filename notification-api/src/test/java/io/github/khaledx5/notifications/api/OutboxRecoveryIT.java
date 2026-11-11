package io.github.khaledx5.notifications.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willCallRealMethod;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import io.github.khaledx5.notifications.api.application.EventPublishingException;
import io.github.khaledx5.notifications.api.application.NotificationEventPublisher;
import io.github.khaledx5.notifications.api.application.Outbox;
import io.github.khaledx5.notifications.api.application.OutboxEvent;
import io.github.khaledx5.notifications.events.NotificationRequested;
import io.github.khaledx5.notifications.events.Topics;
import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the outbox is for, against a real MongoDB and a real broker: a notification survives a broker
 * outage and a relay that stops half-way, and its event still goes out.
 *
 * <p>A claim lasts one second here instead of thirty, so a failed publish is tried again quickly.
 */
@SpringBootTest(properties = {"notifications.outbox.claim-duration=1s", "notifications.outbox.poll-interval=100ms"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, OutboxRecoveryIT.PublishedEventsConfiguration.class})
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

    @MockitoSpyBean
    private NotificationEventPublisher publisher;

    @MockitoSpyBean
    private Outbox outbox;

    @Test
    void notificationIsAcceptedWhileTheBrokerIsDownAndPublishedOnceItIsBack() {
        String recipient = uniqueRecipient();
        willThrow(new EventPublishingException("the broker is down", new IllegalStateException()))
                .given(publisher)
                .publishRequested(argThat(eventFor(recipient)));

        UUID id = accept(recipient);

        // The request succeeded although nothing could be published, and the event is waiting.
        await().atMost(TIMEOUT)
                .untilAsserted(() -> verify(publisher, atLeastOnce()).publishRequested(argThat(eventFor(recipient))));
        assertThat(outboxEntry(id)).doesNotContainKey("publishedAt");
        assertThat(published.of(id)).isEmpty();
        assertThat(statusOf(id)).isEqualTo("QUEUED");

        willCallRealMethod().given(publisher).publishRequested(argThat(eventFor(recipient)));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(published.of(id)).hasSize(1));
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(outboxEntry(id)).containsKey("publishedAt"));
        assertThat(outboxEntry(id).getInteger("attempts")).isGreaterThan(1);
        assertThat(published.of(id).getFirst().eventId())
                .hasToString(outboxEntry(id).getString("_id"));
    }

    @Test
    void outageOfOneEventDoesNotLoseTheOnesAcceptedDuringIt() {
        String unlucky = uniqueRecipient();
        willThrow(new EventPublishingException("the broker is down", new IllegalStateException()))
                .willThrow(new EventPublishingException("the broker is down", new IllegalStateException()))
                .willCallRealMethod()
                .given(publisher)
                .publishRequested(argThat(eventFor(unlucky)));

        UUID first = accept(unlucky);
        UUID second = accept(uniqueRecipient());
        UUID third = accept(uniqueRecipient());

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(published.of(first)).hasSize(1);
            assertThat(published.of(second)).hasSize(1);
            assertThat(published.of(third)).hasSize(1);
        });
    }

    @Test
    void eventThatWasPublishedButNotMarkedGoesOutAgainWithTheSameEventId() {
        String recipient = uniqueRecipient();
        // Stands for a relay that stopped right after the broker confirmed the event of this recipient.
        AtomicBoolean markLost = new AtomicBoolean();
        willAnswer(marking -> {
                    UUID eventId = marking.getArgument(0);
                    if (recipient.equals(recipientOf(eventId)) && markLost.compareAndSet(false, true)) {
                        return null;
                    }
                    return marking.callRealMethod();
                })
                .given(outbox)
                .markPublished(any(), any());

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

    private String recipientOf(UUID eventId) {
        return mongo.getCollection("outbox")
                .find(new Document("_id", eventId.toString()))
                .first()
                .getString("recipient");
    }

    private static ArgumentMatcher<OutboxEvent> eventFor(String recipient) {
        return event -> event != null && event.recipient().equals(recipient);
    }

    private static String uniqueRecipient() {
        return "dina-" + UUID.randomUUID() + "@example.com";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PublishedEventsConfiguration {

        @Bean
        PublishedEvents publishedEvents() {
            return new PublishedEvents();
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
