package io.github.khaledx5.notifications.worker.application;

import io.github.khaledx5.notifications.events.DeliveryOutcome;
import java.util.Objects;

/**
 * How the delivery of one notification ended.
 *
 * @param failureReason why it failed, in words that are safe to show to an API client; present
 *     exactly when the outcome is {@link DeliveryOutcome#FAILED}
 */
public record DeliveryResult(DeliveryOutcome outcome, String failureReason) {

    public DeliveryResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        boolean hasReason = failureReason != null && !failureReason.isBlank();
        if (outcome == DeliveryOutcome.FAILED && !hasReason) {
            throw new IllegalArgumentException("a failed delivery needs a failureReason");
        }
        if (outcome == DeliveryOutcome.DELIVERED && failureReason != null) {
            throw new IllegalArgumentException("a successful delivery has no failureReason");
        }
    }

    public static DeliveryResult delivered() {
        return new DeliveryResult(DeliveryOutcome.DELIVERED, null);
    }

    public static DeliveryResult failed(String reason) {
        return new DeliveryResult(DeliveryOutcome.FAILED, reason);
    }

    public boolean isDelivered() {
        return outcome == DeliveryOutcome.DELIVERED;
    }
}
