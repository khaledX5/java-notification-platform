package io.github.khaledx5.notifications.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.willThrow;

import io.github.khaledx5.notifications.events.DeliveryChannel;
import io.github.khaledx5.notifications.events.DeliveryStatusChanged;
import io.github.khaledx5.notifications.events.NotificationRequested;
import io.github.khaledx5.notifications.events.Topics;
import io.github.khaledx5.notifications.worker.application.ChannelSender;
import io.github.khaledx5.notifications.worker.application.DeliveryException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Tracing and metrics of the worker against a real broker: a status event belongs to the trace of
 * the request event that caused it, also after a retry, and the delivery counters can be scraped.
 *
 * <p>Spring Boot switches trace propagation and the Prometheus registry off in tests; the two
 * annotations switch them back on.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "notifications.delivery.retry.attempts=3",
            "notifications.delivery.retry.delay=200ms",
            "notifications.delivery.retry.multiplier=1",
            "notifications.delivery.retry.max-delay=1s"
        })
@AutoConfigureMetrics(export = true)
@AutoConfigureTracing
@Import({TestcontainersConfiguration.class, ObservabilityIT.StatusRecordsConfiguration.class})
class ObservabilityIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ProducerFactory<String, Object> producerFactory;

    @Autowired
    private StatusRecords statuses;

    @MockitoSpyBean(name = "simulatedPushSender")
    private ChannelSender push;

    @Test
    void statusEventBelongsToTheTraceOfTheRequestEvent() {
        NotificationRequested event = requested(DeliveryChannel.SMS, "+201000000000");

        publish(event, TRACEPARENT);

        assertThat(statuses.traceparentOf(event.notificationId())).matches("00-" + TRACE_ID + "-[0-9a-f]{16}-01");
    }

    @Test
    void traceSurvivesARetry() {
        String token = "device-" + UUID.randomUUID();
        willThrow(new DeliveryException("The push provider is down"))
                .willCallRealMethod()
                .given(push)
                .send(argThat(message -> message != null && message.recipient().equals(token)));
        NotificationRequested event = requested(DeliveryChannel.PUSH, token);

        publish(event, TRACEPARENT);

        assertThat(statuses.traceparentOf(event.notificationId())).matches("00-" + TRACE_ID + "-[0-9a-f]{16}-01");
    }

    @Test
    void deliveryCountersAreExposedForPrometheus() {
        NotificationRequested event = requested(DeliveryChannel.SMS, "+201000000000");
        publish(event, TRACEPARENT);
        statuses.traceparentOf(event.notificationId());

        await().atMost(TIMEOUT).untilAsserted(() -> {
            HttpResponse<String> scrape = get("/actuator/prometheus");
            assertThat(scrape.statusCode()).isEqualTo(200);
            assertThat(scrape.body())
                    .containsPattern(
                            "notifications_delivery_attempts_total\\{[^}]*channel=\"sms\"[^}]*result=\"delivered\"");
        });
    }

    private void publish(NotificationRequested event, String traceparent) {
        ProducerRecord<String, Object> producerRecord = new ProducerRecord<>(
                Topics.NOTIFICATIONS_REQUESTED, event.notificationId().toString(), event);
        producerRecord.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
        // A template of its own, without observation: the one of the application would replace the
        // header with the trace of this test thread.
        new KafkaTemplate<>(producerFactory).send(producerRecord).join();
    }

    private HttpResponse<String> get(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static NotificationRequested requested(DeliveryChannel channel, String recipient) {
        return new NotificationRequested(
                UUID.randomUUID(), UUID.randomUUID(), recipient, channel, null, "Code 4821", Instant.now());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StatusRecordsConfiguration {

        @Bean
        StatusRecords statusRecords() {
            return new StatusRecords();
        }
    }

    /** Plays the part of notification-api and keeps the trace header each status event arrived with. */
    static class StatusRecords {

        private final Map<UUID, String> traceparentByNotification = new ConcurrentHashMap<>();

        @KafkaListener(
                topics = Topics.NOTIFICATIONS_STATUS,
                groupId = "worker-observability-it",
                properties =
                        "spring.json.value.default.type=io.github.khaledx5.notifications.events.DeliveryStatusChanged")
        void onStatusChanged(ConsumerRecord<String, DeliveryStatusChanged> status) {
            Header traceparent = status.headers().lastHeader("traceparent");
            traceparentByNotification.put(
                    status.value().notificationId(),
                    traceparent == null ? "" : new String(traceparent.value(), StandardCharsets.UTF_8));
        }

        String traceparentOf(UUID notificationId) {
            await().atMost(TIMEOUT).until(() -> traceparentByNotification.containsKey(notificationId));
            return traceparentByNotification.get(notificationId);
        }
    }
}
