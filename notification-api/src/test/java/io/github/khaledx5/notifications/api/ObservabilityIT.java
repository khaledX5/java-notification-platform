package io.github.khaledx5.notifications.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.khaledx5.notifications.events.NotificationRequested;
import io.github.khaledx5.notifications.events.Topics;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.bson.Document;
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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tracing and metrics against real infrastructure and over real HTTP: the trace of a request
 * survives the outbox and reaches Kafka, and the business metrics are exposed for scraping.
 *
 * <p>The application listens on a port here instead of being called through MockMvc, because the
 * trace of a request is started by the filter chain of the server. Spring Boot switches trace
 * propagation and the Prometheus registry off in tests; the two annotations switch them back on.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMetrics(export = true)
@AutoConfigureTracing
@Import({TestcontainersConfiguration.class, ObservabilityIT.PublishedRecordsConfiguration.class})
class ObservabilityIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String CALLER_SPAN_ID = "00f067aa0ba902b7";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + CALLER_SPAN_ID + "-01";
    private static final String SMS_JSON = """
            {"recipient": "+201000000000", "channel": "SMS", "body": "Code 4821"}
            """;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private MongoTemplate mongo;

    @Autowired
    private PublishedRecords published;

    @Autowired
    private JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void traceOfTheRequestIsStoredWithTheOutboxEntryAndCarriedOnTheKafkaRecord() {
        UUID id = accept(TRACEPARENT);

        assertThat(storedTraceparentOf(id)).contains(TRACE_ID);
        String forwarded = published.traceparentOf(id);
        assertThat(forwarded).matches("00-" + TRACE_ID + "-[0-9a-f]{16}-01");
        // The record is sent by a new span of the same trace, not by the span of the caller.
        assertThat(forwarded).doesNotContain(CALLER_SPAN_ID);
    }

    @Test
    void requestWithoutATraceStartsOneThatReachesTheKafkaRecord() {
        UUID id = accept(null);

        String stored = storedTraceparentOf(id);
        String forwarded = published.traceparentOf(id);

        assertThat(stored).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
        assertThat(traceIdOf(forwarded)).isEqualTo(traceIdOf(stored));
    }

    @Test
    void businessMetricsAreExposedAsJsonAndForPrometheus() {
        UUID id = accept(null);
        published.traceparentOf(id);

        HttpResponse<String> accepted = get("/actuator/metrics/notifications.accepted");
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(json.readTree(accepted.body())
                        .get("measurements")
                        .get(0)
                        .get("value")
                        .asDouble())
                .isGreaterThanOrEqualTo(1);
        assertThat(get("/actuator/metrics/notifications.outbox.waiting").statusCode())
                .isEqualTo(200);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            HttpResponse<String> scrape = get("/actuator/prometheus");
            assertThat(scrape.statusCode()).isEqualTo(200);
            assertThat(scrape.body())
                    .contains("notifications_accepted_total{")
                    .contains("notifications_outbox_events_total{")
                    .contains("notifications_outbox_waiting")
                    .contains("notifications_outbox_publish_seconds");
        });
    }

    private UUID accept(String traceparent) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/api/v1/notifications"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(SMS_JSON));
        if (traceparent != null) {
            request.header("traceparent", traceparent);
        }
        HttpResponse<String> created = send(request.build());
        assertThat(created.statusCode()).isEqualTo(201);
        return UUID.fromString(json.readTree(created.body()).get("id").asString());
    }

    private String storedTraceparentOf(UUID notificationId) {
        Document entry = mongo.getCollection("outbox")
                .find(new Document("notificationId", notificationId.toString()))
                .first();
        Document trace = entry.get("trace", Document.class);
        assertThat(trace).as("trace stored with the outbox entry").isNotNull();
        return trace.getString("traceparent");
    }

    private HttpResponse<String> get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String traceIdOf(String traceparent) {
        return traceparent.split("-")[1];
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PublishedRecordsConfiguration {

        @Bean
        PublishedRecords publishedRecords() {
            return new PublishedRecords();
        }
    }

    /** Plays the part of the delivery worker and keeps the trace header each event arrived with. */
    static class PublishedRecords {

        private final Map<UUID, String> traceparentByNotification = new ConcurrentHashMap<>();

        @KafkaListener(
                topics = Topics.NOTIFICATIONS_REQUESTED,
                groupId = "observability-it",
                properties =
                        "spring.json.value.default.type=io.github.khaledx5.notifications.events.NotificationRequested")
        void onRequested(ConsumerRecord<String, NotificationRequested> published) {
            Header traceparent = published.headers().lastHeader("traceparent");
            traceparentByNotification.put(
                    published.value().notificationId(),
                    traceparent == null ? "" : new String(traceparent.value(), StandardCharsets.UTF_8));
        }

        String traceparentOf(UUID notificationId) {
            await().atMost(TIMEOUT).until(() -> traceparentByNotification.containsKey(notificationId));
            return traceparentByNotification.get(notificationId);
        }
    }
}
