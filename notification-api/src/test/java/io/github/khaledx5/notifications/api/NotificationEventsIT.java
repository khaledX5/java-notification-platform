package io.github.khaledx5.notifications.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.khaledx5.notifications.events.DeliveryChannel;
import io.github.khaledx5.notifications.events.DeliveryStatusChanged;
import io.github.khaledx5.notifications.events.NotificationRequested;
import io.github.khaledx5.notifications.events.Topics;
import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Kafka side of the API against a real broker: accepted notifications go out as request events,
 * and status events coming back change what the API reports.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, NotificationEventsIT.RequestedEventsConfiguration.class})
class NotificationEventsIT {

    private static final String NOTIFICATIONS = "/api/v1/notifications";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private KafkaTemplate<String, Object> kafka;

    @Autowired
    private RequestedEvents requestedEvents;

    @Autowired
    private JsonMapper json;

    @Test
    void acceptedNotificationIsPublishedAsARequestEventKeyedByItsId() {
        UUID id = accept("""
                {
                  "recipient": "dina@example.com",
                  "channel": "EMAIL",
                  "subject": "Order shipped",
                  "body": "Your order is on its way"
                }
                """);

        ConsumerRecord<String, NotificationRequested> published = requestedEvents.awaitFor(id);

        assertThat(published.key()).isEqualTo(id.toString());
        NotificationRequested event = published.value();
        assertThat(event.eventId()).isNotNull();
        assertThat(event.recipient()).isEqualTo("dina@example.com");
        assertThat(event.channel()).isEqualTo(DeliveryChannel.EMAIL);
        assertThat(event.subject()).isEqualTo("Order shipped");
        assertThat(event.body()).isEqualTo("Your order is on its way");
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    void notificationFromATemplateIsPublishedWithItsRenderedText() {
        String key = "events-" + UUID.randomUUID();
        mvc.put()
                .uri("/api/v1/templates/{key}", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"body": "Your code is {{code}}"}
                        """)
                .exchange();

        UUID id = accept("""
                {"recipient": "+201000000000", "channel": "SMS", "templateKey": "%s", "variables": {"code": "4821"}}
                """.formatted(key));

        NotificationRequested event = requestedEvents.awaitFor(id).value();
        assertThat(event.channel()).isEqualTo(DeliveryChannel.SMS);
        assertThat(event.subject()).isNull();
        assertThat(event.body()).isEqualTo("Your code is 4821");
    }

    @Test
    void deliveredStatusEventMarksTheNotificationAsDelivered() {
        UUID id = acceptSms();

        publish(DeliveryStatusChanged.delivered(id, Instant.now()));

        awaitStatus(id, "DELIVERED");
        assertThat(mvc.get().uri(NOTIFICATIONS + "/{id}", id)).bodyJson().doesNotHavePath("$.failureReason");
    }

    @Test
    void failedStatusEventMarksTheNotificationAsFailedWithTheReason() {
        UUID id = acceptSms();

        publish(DeliveryStatusChanged.failed(id, "The recipient is not an E.164 phone number", Instant.now()));

        awaitStatus(id, "FAILED");
        assertThat(mvc.get().uri(NOTIFICATIONS + "/{id}", id))
                .bodyJson()
                .extractingPath("$.failureReason")
                .isEqualTo("The recipient is not an E.164 phone number");
    }

    @Test
    void repeatedAndLateStatusEventsDoNotChangeAFinalStatus() {
        UUID first = acceptSms();
        UUID second = acceptSms();
        publish(DeliveryStatusChanged.delivered(first, Instant.now()));
        awaitStatus(first, "DELIVERED");

        publish(DeliveryStatusChanged.delivered(first, Instant.now()));
        publish(DeliveryStatusChanged.failed(first, "gateway is down", Instant.now()));
        // The status topic has one partition here, so this event is handled after the two above.
        publish(DeliveryStatusChanged.delivered(second, Instant.now()));
        awaitStatus(second, "DELIVERED");

        MvcTestResult firstNow = mvc.get().uri(NOTIFICATIONS + "/{id}", first).exchange();
        assertThat(firstNow).bodyJson().extractingPath("$.status").isEqualTo("DELIVERED");
        assertThat(firstNow).bodyJson().doesNotHavePath("$.failureReason");
    }

    @Test
    void statusEventForAnUnknownNotificationDoesNotBlockTheOnesBehindIt() {
        UUID id = acceptSms();

        publish(DeliveryStatusChanged.delivered(UUID.randomUUID(), Instant.now()));
        publish(DeliveryStatusChanged.delivered(id, Instant.now()));

        awaitStatus(id, "DELIVERED");
    }

    private UUID acceptSms() {
        return accept("""
                {"recipient": "+201000000000", "channel": "SMS", "body": "Code 4821"}
                """);
    }

    private UUID accept(String body) {
        MvcTestResult created = mvc.post()
                .uri(NOTIFICATIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
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

    private void publish(DeliveryStatusChanged event) {
        kafka.send(Topics.NOTIFICATIONS_STATUS, event.notificationId().toString(), event)
                .join();
    }

    private void awaitStatus(UUID id, String status) {
        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(mvc.get().uri(NOTIFICATIONS + "/{id}", id))
                        .bodyJson()
                        .extractingPath("$.status")
                        .isEqualTo(status));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RequestedEventsConfiguration {

        @Bean
        RequestedEvents requestedEvents() {
            return new RequestedEvents();
        }
    }

    /** Plays the part of the delivery worker: collects the request events the API publishes. */
    static class RequestedEvents {

        private final Map<UUID, ConsumerRecord<String, NotificationRequested>> byNotification =
                new ConcurrentHashMap<>();

        @KafkaListener(
                topics = Topics.NOTIFICATIONS_REQUESTED,
                groupId = "notification-events-it",
                properties =
                        "spring.json.value.default.type=io.github.khaledx5.notifications.events.NotificationRequested")
        void onRequested(ConsumerRecord<String, NotificationRequested> published) {
            byNotification.put(published.value().notificationId(), published);
        }

        ConsumerRecord<String, NotificationRequested> awaitFor(UUID notificationId) {
            await().atMost(TIMEOUT).until(() -> byNotification.containsKey(notificationId));
            return byNotification.get(notificationId);
        }
    }
}
