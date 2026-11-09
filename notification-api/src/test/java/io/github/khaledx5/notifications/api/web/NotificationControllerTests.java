package io.github.khaledx5.notifications.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.khaledx5.notifications.api.application.NotificationService;
import io.github.khaledx5.notifications.api.domain.Channel;
import io.github.khaledx5.notifications.api.domain.Notification;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
        given(notifications.findById(sms.id())).willReturn(Optional.of(sms));

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
    void unknownNotificationIsNotFound() {
        given(notifications.findById(any())).willReturn(Optional.empty());

        assertThat(mvc.get().uri("/api/v1/notifications/{id}", UUID.randomUUID()))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void malformedIdIsABadRequest() {
        assertThat(mvc.get().uri("/api/v1/notifications/not-a-uuid")).hasStatus(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(notifications);
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
