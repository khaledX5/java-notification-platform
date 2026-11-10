package io.github.khaledx5.notifications.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The worker finished with a notification: it was delivered, or it failed for good.
 *
 * @param eventId unique per event; consumers use it to recognise a redelivered event
 * @param failureReason why the delivery failed; present exactly when the outcome is {@link
 *     DeliveryOutcome#FAILED}
 */
public record DeliveryStatusChanged(
        UUID eventId, UUID notificationId, DeliveryOutcome outcome, String failureReason, Instant occurredAt) {

    public DeliveryStatusChanged {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(notificationId, "notificationId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        boolean hasReason = failureReason != null && !failureReason.isBlank();
        if (outcome == DeliveryOutcome.FAILED && !hasReason) {
            throw new IllegalArgumentException("a failed delivery needs a failureReason");
        }
        if (outcome == DeliveryOutcome.DELIVERED && failureReason != null) {
            throw new IllegalArgumentException("a successful delivery has no failureReason");
        }
    }

    public static DeliveryStatusChanged delivered(UUID notificationId, Instant occurredAt) {
        return new DeliveryStatusChanged(
                UUID.randomUUID(), notificationId, DeliveryOutcome.DELIVERED, null, occurredAt);
    }

    public static DeliveryStatusChanged failed(UUID notificationId, String failureReason, Instant occurredAt) {
        return new DeliveryStatusChanged(
                UUID.randomUUID(), notificationId, DeliveryOutcome.FAILED, failureReason, occurredAt);
    }
}
