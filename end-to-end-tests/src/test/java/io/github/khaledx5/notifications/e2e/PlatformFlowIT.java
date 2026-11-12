package io.github.khaledx5.notifications.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The platform from the outside, the way a client and a recipient see it: a request goes in over
 * HTTP, a message arrives in an inbox, and the status of the notification says so.
 *
 * <p>Between those two ends lie both services, the outbox, Kafka in both directions, Redis and SMTP.
 * The tests of each module cover the details; these prove that the parts work together when they run
 * as separate processes with their production configuration.
 */
class PlatformFlowIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final Platform platform = new Platform();

    @BeforeAll
    static void startPlatform() {
        platform.start();
    }

    @AfterAll
    static void stopPlatform() {
        platform.close();
    }

    @Test
    void emailRequestedOverHttpArrivesInTheInboxAndIsReportedAsDelivered() {
        String recipient = uniqueEmail();

        HttpResponse<String> accepted = post("/api/v1/notifications", """
                {"recipient": "%s", "channel": "EMAIL", "subject": "Order shipped", "body": "Your order is on its way"}
                """.formatted(recipient));

        assertThat(accepted.statusCode()).isEqualTo(201);
        assertThat(json(accepted).get("status").asString()).isEqualTo("QUEUED");
        String location = accepted.headers().firstValue("Location").orElseThrow();
        awaitStatus(location, "DELIVERED");

        JsonNode inbox = emailsTo(recipient);
        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0).get("Subject").asString()).isEqualTo("Order shipped");
        assertThat(inbox.get(0).get("Snippet").asString()).isEqualTo("Your order is on its way");
    }

    @Test
    void notificationFromATemplateIsDeliveredWithItsRenderedText() {
        String recipient = uniqueEmail();
        String templateKey = "e2e-" + UUID.randomUUID();
        HttpResponse<String> template = put("/api/v1/templates/" + templateKey, """
                {"subject": "Order {{orderId}} shipped", "body": "Hi {{name}}, order {{orderId}} is on its way."}
                """);
        assertThat(template.statusCode()).isEqualTo(200);

        HttpResponse<String> accepted = post("/api/v1/notifications", """
                {
                  "recipient": "%s",
                  "channel": "EMAIL",
                  "templateKey": "%s",
                  "variables": {"orderId": "A-1042", "name": "Dina"}
                }
                """.formatted(recipient, templateKey));

        assertThat(accepted.statusCode()).isEqualTo(201);
        awaitStatus(accepted.headers().firstValue("Location").orElseThrow(), "DELIVERED");
        JsonNode inbox = emailsTo(recipient);
        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0).get("Subject").asString()).isEqualTo("Order A-1042 shipped");
        assertThat(inbox.get(0).get("Snippet").asString()).isEqualTo("Hi Dina, order A-1042 is on its way.");
    }

    @Test
    void notificationThatCanNeverBeDeliveredEndsAsFailedWithTheReason() {
        HttpResponse<String> accepted = post("/api/v1/notifications", """
                {"recipient": "not-a-phone-number", "channel": "SMS", "body": "Code 4821"}
                """);

        assertThat(accepted.statusCode()).isEqualTo(201);
        String location = accepted.headers().firstValue("Location").orElseThrow();
        awaitStatus(location, "FAILED");
        assertThat(json(get(location)).get("failureReason").asString())
                .isEqualTo("The recipient is not an E.164 phone number");
    }

    @Test
    void requestRepeatedWithTheSameIdempotencyKeyIsDeliveredOnce() {
        String recipient = uniqueEmail();
        String key = "e2e-" + UUID.randomUUID();
        String body = """
                {"recipient": "%s", "channel": "EMAIL", "subject": "Your invoice", "body": "Invoice 2026-118 is ready"}
                """.formatted(recipient);

        HttpResponse<String> first = post("/api/v1/notifications", body, "Idempotency-Key", key);
        HttpResponse<String> repeat = post("/api/v1/notifications", body, "Idempotency-Key", key);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(repeat.statusCode()).isEqualTo(201);
        assertThat(repeat.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(json(repeat).get("id").asString())
                .isEqualTo(json(first).get("id").asString());
        awaitStatus(first.headers().firstValue("Location").orElseThrow(), "DELIVERED");
        assertThat(emailsTo(recipient)).hasSize(1);
    }

    @Test
    void traceOfTheHttpRequestReachesTheDeliveryInTheOtherService() {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String recipient = uniqueEmail();

        HttpResponse<String> accepted = post(
                "/api/v1/notifications",
                """
                {"recipient": "%s", "channel": "EMAIL", "subject": "Welcome", "body": "Good to have you"}
                """.formatted(recipient),
                "traceparent",
                "00-" + traceId + "-00f067aa0ba902b7-01");

        awaitStatus(accepted.headers().firstValue("Location").orElseThrow(), "DELIVERED");
        // The worker never saw the HTTP request. The trace id reached it through the outbox entry and
        // the Kafka record, and it writes that id into the log line of the delivery.
        String notificationId = json(accepted).get("id").asString();
        assertThat(platform.workerLogs().lines().filter(line -> line.contains(notificationId)))
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line).contains(traceId));
    }

    @Test
    void bothServicesExposeTheirBusinessMetrics() {
        HttpResponse<String> accepted = post("/api/v1/notifications", """
                {"recipient": "%s", "channel": "EMAIL", "subject": "Reminder", "body": "Your trial ends tomorrow"}
                """.formatted(uniqueEmail()));
        awaitStatus(accepted.headers().firstValue("Location").orElseThrow(), "DELIVERED");

        String apiMetrics = send(HttpRequest.newBuilder(URI.create(platform.apiUrl() + "/actuator/prometheus"))
                        .GET()
                        .build())
                .body();
        String workerMetrics = send(HttpRequest.newBuilder(URI.create(platform.workerUrl() + "/actuator/prometheus"))
                        .GET()
                        .build())
                .body();

        assertThat(apiMetrics)
                .contains("notifications_accepted_total{channel=\"email\"}")
                .contains("notifications_outbox_events_total{result=\"published\"}")
                .contains("notifications_completed_total{status=\"delivered\"}");
        assertThat(workerMetrics)
                .contains("notifications_delivery_attempts_total{channel=\"email\",result=\"delivered\"}");
    }

    private static void awaitStatus(String location, String status) {
        await().atMost(TIMEOUT)
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() ->
                        assertThat(json(get(location)).get("status").asString()).isEqualTo(status));
    }

    /** What the fake SMTP server received for this address, read through its HTTP API. */
    private static JsonNode emailsTo(String recipient) {
        String query = URLEncoder.encode("to:" + recipient, StandardCharsets.UTF_8);
        HttpResponse<String> found =
                send(HttpRequest.newBuilder(URI.create(platform.mailpitUrl() + "/api/v1/search?query=" + query))
                        .GET()
                        .build());
        assertThat(found.statusCode()).isEqualTo(200);
        return json(found).get("messages");
    }

    private static HttpResponse<String> post(String path, String body, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(platform.apiUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return send(request.build());
    }

    private static HttpResponse<String> put(String path, String body) {
        return send(HttpRequest.newBuilder(URI.create(platform.apiUrl() + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    /** Follows the Location header the API returned, whatever host name the API believes it has. */
    private static HttpResponse<String> get(String location) {
        URI path = URI.create(location);
        return send(HttpRequest.newBuilder(URI.create(platform.apiUrl() + path.getRawPath()))
                .GET()
                .build());
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static String uniqueEmail() {
        return "dina-" + UUID.randomUUID() + "@example.com";
    }
}
