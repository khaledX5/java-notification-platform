package io.github.khaledx5.notifications.api.domain;

import java.util.Set;

/**
 * Lifecycle of a notification.
 *
 * <pre>
 * PENDING --> QUEUED --> DELIVERED
 *    |           |
 *    +-----------+-----> FAILED
 * </pre>
 */
public enum NotificationStatus {
    /** Accepted by the API, not yet handed to the broker. */
    PENDING,
    /** Published to the broker, waiting for a delivery result. */
    QUEUED,
    /** Confirmed as delivered by the worker. Terminal. */
    DELIVERED,
    /** Could not be queued or delivered. Terminal. */
    FAILED;

    public boolean canTransitionTo(NotificationStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }

    private Set<NotificationStatus> allowedNext() {
        return switch (this) {
            case PENDING -> Set.of(QUEUED, FAILED);
            case QUEUED -> Set.of(DELIVERED, FAILED);
            case DELIVERED, FAILED -> Set.of();
        };
    }
}
