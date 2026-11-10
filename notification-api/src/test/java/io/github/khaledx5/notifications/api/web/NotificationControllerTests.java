package io.github.khaledx5.notifications.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.khaledx5.notifications.api.application.NotificationNotFoundException;
import io.github.khaledx5.notifications.api.application.NotificationNotQueuedException;
import io.github.khaledx5.notifications.api.application.NotificationService;
import io.github.khaledx5.notifications.api.application.UnknownTemplateException;
import io.github.khaledx5.notifications.api.domain.Channel;
import io.github.khaledx5.notifications.api.domain.MissingTemplateVariableException;
import io.github.khaledx5.notifications.api.domain.Notification;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@WebMvcTest(NotificationController.class)
class NotificationControllerTests {

    private static final Instant CREATED = Instant.parse("2026-01-10T09:00:00.123Z");

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private NotificationService notifications;

    @Test
    void createsANotificationAndPointsToIt() {
        Notification email = Notification.request(
                "dina@example.com", Channel.EMAIL, "Order shipped", "Your order is on its way", CREATED);
        given(notifications.request("dina@example.com", Channel.EMAIL, "Order shipped", "Your order is on its way"))
                .willReturn(email);

        MvcTestResult result = post("""
                {
                  "recipient": "dina@example.com",
                  "channel": "EMAIL",
                  "subject": "Order shipped",
                  "body": "Your order is on its way"
                }
                """);

        assertThat(result)
                .hasStatus(HttpStatus.CREATED)
                .hasHeader("Location", "http://localhost/api/v1/notifications/" + email.id());
        assertThat(result).bodyJson().isLenientlyEqualTo("""
                        {
                          "id": "%s",
                          "recipient": "dina@example.com",
                          "channel": "EMAIL",
                          "subject": "Order shipped",
                          "body": "Your order is on its way",
                          "status": "PENDING",
                          "createdAt": "2026-01-10T09:00:00.123Z",
                          "updatedAt": "2026-01-10T09:00:00.123Z"
                        }
                        """.formatted(email.id()));
    }

    @Test
    void leavesOutFieldsWithoutAValue() {
        Notification sms = pendingSms();
        given(notifications.request("+201000000000", Channel.SMS, null, "Code 4821"))
                .willReturn(sms);

        MvcTestResult result = post("""
                {"recipient": "+201000000000", "channel": "SMS", "body": "Code 4821"}
                """);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().doesNotHavePath("$.subject");
        assertThat(result).bodyJson().doesNotHavePath("$.failureReason");
    }

    @ParameterizedTest
    @ValueSource(strings = {"""
                {"channel": "SMS", "body": "Code 4821"}
                """, """
                {"recipient": "  ", "channel": "SMS", "body": "Code 4821"}
                """, """
                {"recipient": "+201000000000", "body": "Code 4821"}
                """, """
                {"recipient": "+201000000000", "channel": "FAX", "body": "Code 4821"}
                """, """
                {"recipient": "+201000000000", "channel": "SMS", "body": ""}
                """, """
                {"recipient": "+201000000000", "channel": "SMS"
                """})
    void rejectsInvalidBodies(String json) {
        assertThat(post(json)).hasStatus(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(notifications);
    }

    @Test
    void rejectsABodyThatIsTooLong() {
        String json = """
                {"recipient": "+201000000000", "channel": "SMS", "body": "%s"}
                """.formatted("x".repeat(CreateNotificationRequest.MAX_BODY_LENGTH + 1));

        assertThat(post(json)).hasStatus(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(notifications);
    }

    @Test
    void returnsAStoredNotification() {
        Notification sms = pendingSms();
        sms.markQueued(CREATED.plusSeconds(1));
        sms.markFailed("provider rejected the number", CREATED.plusSeconds(2));
        given(notifications.getById(sms.id())).willReturn(sms);

        MvcTestResult result =
                mvc.get().uri("/api/v1/notifications/{id}", sms.id()).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().isLenientlyEqualTo("""
                        {
                          "id": "%s",
                          "status": "FAILED",
                          "failureReason": "provider rejected the number",
                          "createdAt": "2026-01-10T09:00:00.123Z",
                          "updatedAt": "2026-01-10T09:00:02.123Z"
                        }
                        """.formatted(sms.id()));
    }

    @Test
    void unknownNotificationIsANotFoundProblem() {
        UUID unknown = UUID.randomUUID();
        given(notifications.getById(unknown)).willThrow(new NotificationNotFoundException(unknown));

        MvcTestResult result =
                mvc.get().uri("/api/v1/notifications/{id}", unknown).exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().isLenientlyEqualTo("""
                        {
                          "title": "Notification not found",
                          "status": 404,
                          "detail": "No notification with id %s",
                          "instance": "/api/v1/notifications/%s"
                        }
                        """.formatted(unknown, unknown));
    }

    @Test
    void malformedIdIsABadRequestProblem() {
        MvcTestResult result = mvc.get().uri("/api/v1/notifications/not-a-uuid").exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(400);
        verifyNoInteractions(notifications);
    }

    @Test
    void validationProblemListsEveryRejectedField() {
        MvcTestResult result = post("""
                {"recipient": "", "body": "Code 4821"}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().isLenientlyEqualTo("""
                        {
                          "title": "Bad Request",
                          "status": 400,
                          "detail": "Invalid request content.",
                          "instance": "/api/v1/notifications",
                          "errors": [
                            {"field": "channel", "message": "must not be null"},
                            {"field": "recipient", "message": "must not be blank"}
                          ]
                        }
                        """);
    }

    @Test
    void unreadableJsonIsABadRequestProblem() {
        MvcTestResult result = post("{\"recipient\": ");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().doesNotHavePath("$.errors");
    }

    @Test
    void unexpectedFailureIsAnInternalErrorProblemWithoutDetails() {
        given(notifications.getById(any())).willThrow(new IllegalStateException("mongo password=secret"));

        MvcTestResult result =
                mvc.get().uri("/api/v1/notifications/{id}", UUID.randomUUID()).exchange();

        assertThat(result)
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("An unexpected error occurred.");
        assertThat(result).bodyText().doesNotContain("secret");
    }

    @Test
    void notificationThatCouldNotBeQueuedIsAServiceUnavailableProblem() {
        UUID id = UUID.randomUUID();
        given(notifications.request("+201000000000", Channel.SMS, null, "Code 4821"))
                .willThrow(new NotificationNotQueuedException(id, new IllegalStateException("broker=kafka-1:9092")));

        MvcTestResult result = post("""
                {"recipient": "+201000000000", "channel": "SMS", "body": "Code 4821"}
                """);

        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Notification not queued");
        assertThat(result).bodyJson().extractingPath("$.notificationId").isEqualTo(id.toString());
        assertThat(result).bodyText().doesNotContain("kafka-1");
    }

    @Test
    void createsANotificationFromATemplate() {
        Notification email =
                Notification.request("dina@example.com", Channel.EMAIL, "Order A-1042 shipped", "Hi Dina", CREATED);
        given(notifications.requestFromTemplate(
                        "dina@example.com",
                        Channel.EMAIL,
                        "order-shipped",
                        Map.of("orderId", "A-1042", "name", "Dina")))
                .willReturn(email);

        MvcTestResult result = post("""
                {
                  "recipient": "dina@example.com",
                  "channel": "EMAIL",
                  "templateKey": "order-shipped",
                  "variables": {"orderId": "A-1042", "name": "Dina"}
                }
                """);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.subject").isEqualTo("Order A-1042 shipped");
        assertThat(result).bodyJson().extractingPath("$.body").isEqualTo("Hi Dina");
    }

    @Test
    void templateWithoutVariablesIsRenderedWithAnEmptyMap() {
        given(notifications.requestFromTemplate("+201000000000", Channel.SMS, "maintenance", Map.of()))
                .willReturn(pendingSms());

        MvcTestResult result = post("""
                {"recipient": "+201000000000", "channel": "SMS", "templateKey": "maintenance"}
                """);

        assertThat(result).hasStatus(HttpStatus.CREATED);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
                    {"recipient": "a@b.co", "channel": "EMAIL", "templateKey": "welcome", "body": "Hi"}        | body      | must not be set together with templateKey
                    {"recipient": "a@b.co", "channel": "EMAIL", "templateKey": "welcome", "subject": "Hi"}     | subject   | must not be set together with templateKey
                    {"recipient": "a@b.co", "channel": "EMAIL"}                                                | body      | must not be blank unless templateKey is set
                    {"recipient": "a@b.co", "channel": "EMAIL", "body": "Hi", "variables": {"name": "Dina"}}   | variables | must not be set without templateKey
                    {"recipient": "a@b.co", "channel": "EMAIL", "templateKey": "Not A Key"}                    | templateKey | must match "[a-z0-9]+(-[a-z0-9]+)*"
                    """)
    void rejectsAnAmbiguousOrMissingContentSource(String json, String field, String message) {
        MvcTestResult result = post(json);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo(field);
        assertThat(result).bodyJson().extractingPath("$.errors[0].message").isEqualTo(message);
        verifyNoInteractions(notifications);
    }

    @Test
    void unknownTemplateIsAnUnprocessableProblem() {
        given(notifications.requestFromTemplate(any(), any(), any(), any()))
                .willThrow(new UnknownTemplateException("no-such-template"));

        MvcTestResult result = post("""
                {"recipient": "+201000000000", "channel": "SMS", "templateKey": "no-such-template"}
                """);

        assertThat(result).hasStatus(422).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().isLenientlyEqualTo("""
                {
                  "title": "Unknown template",
                  "status": 422,
                  "detail": "Template 'no-such-template' does not exist"
                }
                """);
    }

    @Test
    void missingTemplateVariablesAreListedInAnUnprocessableProblem() {
        given(notifications.requestFromTemplate(any(), any(), any(), any()))
                .willThrow(new MissingTemplateVariableException(
                        "order-shipped", new LinkedHashSet<>(List.of("orderId", "name"))));

        MvcTestResult result = post("""
                {"recipient": "dina@example.com", "channel": "EMAIL", "templateKey": "order-shipped"}
                """);

        assertThat(result).hasStatus(422).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().isLenientlyEqualTo("""
                {
                  "title": "Missing template variables",
                  "status": 422,
                  "missingVariables": ["orderId", "name"]
                }
                """);
    }

    private MvcTestResult post(String json) {
        return mvc.post()
                .uri("/api/v1/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private static Notification pendingSms() {
        return Notification.request("+201000000000", Channel.SMS, null, "Code 4821", CREATED);
    }
}
