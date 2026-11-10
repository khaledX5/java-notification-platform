package io.github.khaledx5.notifications.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class NotificationRequestedTest {

    private static final UUID EVENT_ID = UUID.fromString("7b0c0a52-6f0e-4d0b-9a55-0a4d3f4f1c11");
    private static final UUID NOTIFICATION_ID = UUID.fromString("2f9d3c1e-8a47-4b55-b0c8-5e1d7a9f6b22");
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-10T09:00:00.123Z");

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void wireFormatUsesStableFieldNamesAndIsoTimestamps() {
        NotificationRequested event = new NotificationRequested(
                EVENT_ID,
                NOTIFICATION_ID,
                "dina@example.com",
                DeliveryChannel.EMAIL,
                "Order shipped",
                "Your order is on its way",
                OCCURRED_AT);

        assertThat(json.readTree(json.writeValueAsString(event))).isEqualTo(json.readTree("""
                {
                  "eventId": "7b0c0a52-6f0e-4d0b-9a55-0a4d3f4f1c11",
                  "notificationId": "2f9d3c1e-8a47-4b55-b0c8-5e1d7a9f6b22",
                  "recipient": "dina@example.com",
                  "channel": "EMAIL",
                  "subject": "Order shipped",
                  "body": "Your order is on its way",
                  "occurredAt": "2026-01-10T09:00:00.123Z"
                }
                """));
    }

    @Test
    void survivesAJsonRoundTrip() {
        NotificationRequested event = new NotificationRequested(
                EVENT_ID, NOTIFICATION_ID, "+201000000000", DeliveryChannel.SMS, null, "Code 4821", OCCURRED_AT);

        NotificationRequested read = json.readValue(json.writeValueAsString(event), NotificationRequested.class);

        assertThat(read).isEqualTo(event);
    }

    @Test
    void subjectIsOptional() {
        NotificationRequested read = json.readValue("""
                {
                  "eventId": "7b0c0a52-6f0e-4d0b-9a55-0a4d3f4f1c11",
                  "notificationId": "2f9d3c1e-8a47-4b55-b0c8-5e1d7a9f6b22",
                  "recipient": "device-token-1",
                  "channel": "PUSH",
                  "body": "You have a new message",
                  "occurredAt": "2026-01-10T09:00:00.123Z"
                }
                """, NotificationRequested.class);

        assertThat(read.subject()).isNull();
        assertThat(read.channel()).isEqualTo(DeliveryChannel.PUSH);
    }

    @Test
    void requiredFieldsAreChecked() {
        assertThatNullPointerException()
                .isThrownBy(() -> new NotificationRequested(
                        EVENT_ID, null, "dina@example.com", DeliveryChannel.EMAIL, null, "Hello", OCCURRED_AT))
                .withMessageContaining("notificationId");
        assertThatNullPointerException()
                .isThrownBy(() -> new NotificationRequested(
                        EVENT_ID, NOTIFICATION_ID, "dina@example.com", null, null, "Hello", OCCURRED_AT))
                .withMessageContaining("channel");
    }
}
