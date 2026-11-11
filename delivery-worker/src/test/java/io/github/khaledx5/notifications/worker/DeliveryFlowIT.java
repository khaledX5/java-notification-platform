package io.github.khaledx5.notifications.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.khaledx5.notifications.events.DeliveryChannel;
import io.github.khaledx5.notifications.events.DeliveryOutcome;
import io.github.khaledx5.notifications.events.DeliveryStatusChanged;
import io.github.khaledx5.notifications.events.NotificationRequested;
import io.github.khaledx5.notifications.events.Topics;
import io.github.khaledx5.notifications.worker.application.ChannelSender;
import io.github.khaledx5.notifications.worker.application.DeliveryException;
import io.github.khaledx5.notifications.worker.application.OutboundMessage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.StreamSupport;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The worker end to end against a real broker and a real SMTP server: request events go in on
 * Kafka, messages come out through the senders, and status events come back on Kafka.
 *
 * <p>Retries use short delays here so that a notification runs out of attempts within a second.
 */
@SpringBootTest(
        properties = {
            "notifications.delivery.retry.attempts=3",
            "notifications.delivery.retry.delay=200ms",
            "notifications.delivery.retry.multiplier=1",
            "notifications.delivery.retry.max-delay=1s"
        })
@Import({TestcontainersConfiguration.class, DeliveryFlowIT.StatusEventsConfiguration.class})
class DeliveryFlowIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int ATTEMPTS = 3;

    @Autowired
    private KafkaTemplate<String, Object> kafka;

    @Autowired
    private StatusEvents statuses;

    @Autowired
    private KafkaContainer kafkaContainer;

    @Autowired
    @Qualifier("mailpitContainer")
    private GenericContainer<?> mailpit;

    @Autowired
    private JsonMapper json;

    @MockitoSpyBean(name = "simulatedPushSender")
    private ChannelSender push;

    @Test
    void emailIsSentOverSmtpAndReportedAsDelivered() {
        String recipient = "dina-" + UUID.randomUUID() + "@example.com";
        NotificationRequested event =
                requested(DeliveryChannel.EMAIL, recipient, "Order shipped", "Your order is on its way");

        publish(event);

        DeliveryStatusChanged status = statuses.awaitFor(event.notificationId());
        assertThat(status.outcome()).isEqualTo(DeliveryOutcome.DELIVERED);
        assertThat(status.failureReason()).isNull();
        JsonNode email = receivedEmailTo(recipient);
        assertThat(email.get("Subject").asString()).isEqualTo("Order shipped");
        assertThat(email.get("From").get("Address").asString()).isEqualTo("no-reply@notifications.local");
        assertThat(email.get("Snippet").asString()).isEqualTo("Your order is on its way");
    }

    @Test
    void smsAndPushAreReportedAsDelivered() {
        NotificationRequested sms = requested(DeliveryChannel.SMS, "+201000000000", null, "Code 4821");
        NotificationRequested pushed = requested(DeliveryChannel.PUSH, "device-" + UUID.randomUUID(), null, "Hello");

        publish(sms);
        publish(pushed);

        assertThat(statuses.awaitFor(sms.notificationId()).outcome()).isEqualTo(DeliveryOutcome.DELIVERED);
        assertThat(statuses.awaitFor(pushed.notificationId()).outcome()).isEqualTo(DeliveryOutcome.DELIVERED);
    }

    @Test
    void notificationThatCanNeverBeDeliveredFailsAtOnceWithTheReason() {
        NotificationRequested event = requested(DeliveryChannel.SMS, "not-a-phone-number", null, "Code 4821");

        publish(event);

        DeliveryStatusChanged status = statuses.awaitFor(event.notificationId());
        assertThat(status.outcome()).isEqualTo(DeliveryOutcome.FAILED);
        assertThat(status.failureReason()).isEqualTo("The recipient is not an E.164 phone number");
    }

    @Test
    void deliveryThatFailsAtFirstIsRetriedUntilItSucceeds() {
        String token = "device-" + UUID.randomUUID();
        DeliveryException providerDown = new DeliveryException("The push provider is down");
        willThrow(providerDown)
                .willThrow(providerDown)
                .willCallRealMethod()
                .given(push)
                .send(argThat(sentTo(token)));
        NotificationRequested event = requested(DeliveryChannel.PUSH, token, null, "You have a new message");

        publish(event);

        DeliveryStatusChanged status = statuses.awaitFor(event.notificationId());
        assertThat(status.outcome()).isEqualTo(DeliveryOutcome.DELIVERED);
        verify(push, times(ATTEMPTS)).send(argThat(sentTo(token)));
    }

    @Test
    void deliveryThatKeepsFailingIsAbandonedAfterTheLastAttempt() {
        String token = "device-" + UUID.randomUUID();
        willThrow(new DeliveryException("The push provider is down"))
                .given(push)
                .send(argThat(sentTo(token)));
        NotificationRequested event = requested(DeliveryChannel.PUSH, token, null, "You have a new message");

        publish(event);

        DeliveryStatusChanged status = statuses.awaitFor(event.notificationId());
        assertThat(status.outcome()).isEqualTo(DeliveryOutcome.FAILED);
        assertThat(status.failureReason()).isEqualTo("Delivery kept failing and was abandoned");
        verify(push, times(ATTEMPTS)).send(argThat(sentTo(token)));
    }

    @Test
    void eventThatArrivesTwiceSendsOnlyOneEmail() {
        String recipient = "dina-" + UUID.randomUUID() + "@example.com";
        NotificationRequested event = requested(DeliveryChannel.EMAIL, recipient, "Order shipped", "On its way");
        NotificationRequested behind = requested(DeliveryChannel.SMS, "+201000000000", null, "Code 4821");

        publish(event);
        publish(event);
        // The topic has one partition here, so this event is handled after both copies above.
        publish(behind);

        statuses.awaitFor(behind.notificationId());
        assertThat(statuses.awaitFor(event.notificationId()).outcome()).isEqualTo(DeliveryOutcome.DELIVERED);
        assertThat(emailsTo(recipient)).hasSize(1);
    }

    @Test
    void eventsWithDifferentIdsForTheSameNotificationAreNotMistakenForDuplicates() {
        String token = "device-" + UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();
        NotificationRequested first = new NotificationRequested(
                UUID.randomUUID(), notificationId, token, DeliveryChannel.PUSH, null, "Hello", Instant.now());
        NotificationRequested second = new NotificationRequested(
                UUID.randomUUID(), notificationId, token, DeliveryChannel.PUSH, null, "Hello", Instant.now());
        NotificationRequested behind = requested(DeliveryChannel.SMS, "+201000000000", null, "Code 4821");

        publish(first);
        publish(second);
        publish(behind);

        statuses.awaitFor(behind.notificationId());
        verify(push, times(2)).send(argThat(sentTo(token)));
    }

    @Test
    void unreadableRecordDoesNotStopTheWorker() {
        publishRaw("this is not a notification event");
        NotificationRequested next = requested(DeliveryChannel.SMS, "+201000000000", null, "Code 4821");

        publish(next);

        assertThat(statuses.awaitFor(next.notificationId()).outcome()).isEqualTo(DeliveryOutcome.DELIVERED);
    }

    private void publish(NotificationRequested event) {
        kafka.send(Topics.NOTIFICATIONS_REQUESTED, event.notificationId().toString(), event)
                .join();
    }

    private void publishRaw(String value) {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            producer.send(new ProducerRecord<>(Topics.NOTIFICATIONS_REQUESTED, "broken", value));
            producer.flush();
        }
    }

    private JsonNode receivedEmailTo(String recipient) {
        return emailsTo(recipient).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("No email was received for " + recipient));
    }

    private List<JsonNode> emailsTo(String recipient) {
        URI inbox = URI.create("http://%s:%d/api/v1/messages"
                .formatted(mailpit.getHost(), mailpit.getMappedPort(TestcontainersConfiguration.MAILPIT_HTTP_PORT)));
        HttpRequest request = HttpRequest.newBuilder(inbox).GET().build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            JsonNode messages = json.readTree(http.send(request, HttpResponse.BodyHandlers.ofString())
                            .body())
                    .get("messages");
            return StreamSupport.stream(messages.spliterator(), false)
                    .filter(message ->
                            message.get("To").get(0).get("Address").asString().equals(recipient))
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static ArgumentMatcher<OutboundMessage> sentTo(String recipient) {
        return message -> message != null && message.recipient().equals(recipient);
    }

    private static NotificationRequested requested(
            DeliveryChannel channel, String recipient, String subject, String body) {
        return new NotificationRequested(
                UUID.randomUUID(), UUID.randomUUID(), recipient, channel, subject, body, Instant.now());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StatusEventsConfiguration {

        @Bean
        StatusEvents statusEvents() {
            return new StatusEvents();
        }
    }

    /** Plays the part of notification-api: collects the status events the worker publishes. */
    static class StatusEvents {

        private final Map<UUID, DeliveryStatusChanged> byNotification = new ConcurrentHashMap<>();

        @KafkaListener(
                topics = Topics.NOTIFICATIONS_STATUS,
                groupId = "delivery-flow-it",
                properties =
                        "spring.json.value.default.type=io.github.khaledx5.notifications.events.DeliveryStatusChanged")
        void onStatusChanged(DeliveryStatusChanged event) {
            byNotification.put(event.notificationId(), event);
        }

        DeliveryStatusChanged awaitFor(UUID notificationId) {
            await().atMost(TIMEOUT).until(() -> byNotification.containsKey(notificationId));
            return byNotification.get(notificationId);
        }
    }
}
