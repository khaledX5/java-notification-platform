package io.github.khaledx5.notifications.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class DeliveryStatusChangedTest {

    private static final UUID NOTIFICATION_ID = UUID.fromString("2f9d3c1e-8a47-4b55-b0c8-5e1d7a9f6b22");
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-10T09:00:05.250Z");

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void deliveredEventHasNoFailureReason() {
        DeliveryStatusChanged event = DeliveryStatusChanged.delivered(NOTIFICATION_ID, OCCURRED_AT);

        assertThat(event.outcome()).isEqualTo(DeliveryOutcome.DELIVERED);
        assertThat(event.failureReason()).isNull();
        assertThat(event.notificationId()).isEqualTo(NOTIFICATION_ID);
    }

    @Test
    void failedEventCarriesItsReason() {
        DeliveryStatusChanged event = DeliveryStatusChanged.failed(NOTIFICATION_ID, "mailbox unavailable", OCCURRED_AT);

        assertThat(event.outcome()).isEqualTo(DeliveryOutcome.FAILED);
        assertThat(event.failureReason()).isEqualTo("mailbox unavailable");
    }

    @Test
    void everyEventGetsItsOwnId() {
        DeliveryStatusChanged first = DeliveryStatusChanged.delivered(NOTIFICATION_ID, OCCURRED_AT);
        DeliveryStatusChanged second = DeliveryStatusChanged.delivered(NOTIFICATION_ID, OCCURRED_AT);

        assertThat(first.eventId()).isNotEqualTo(second.eventId());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void failedEventWithoutAReasonIsRejected(String reason) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DeliveryStatusChanged.failed(NOTIFICATION_ID, reason, OCCURRED_AT));
    }

    @Test
    void deliveredEventWithAReasonIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DeliveryStatusChanged(
                        UUID.randomUUID(),
                        NOTIFICATION_ID,
                        DeliveryOutcome.DELIVERED,
                        "mailbox unavailable",
                        OCCURRED_AT));
    }

    @Test
    void survivesAJsonRoundTrip() {
        DeliveryStatusChanged event = DeliveryStatusChanged.failed(NOTIFICATION_ID, "mailbox unavailable", OCCURRED_AT);

        DeliveryStatusChanged read = json.readValue(json.writeValueAsString(event), DeliveryStatusChanged.class);

        assertThat(read).isEqualTo(event);
    }

    @Test
    void wireFormatUsesStableFieldNames() {
        DeliveryStatusChanged event = DeliveryStatusChanged.delivered(NOTIFICATION_ID, OCCURRED_AT);

        assertThat(json.readTree(json.writeValueAsString(event)).propertyNames())
                .containsExactlyInAnyOrder("eventId", "notificationId", "outcome", "failureReason", "occurredAt");
    }
}
