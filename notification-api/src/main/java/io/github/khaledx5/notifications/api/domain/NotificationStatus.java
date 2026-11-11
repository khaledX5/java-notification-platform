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
    /** Created, not yet queued. A notification leaves this status before it is stored. */
    PENDING,
    /** Stored together with the event that announces it; waiting for a delivery result. */
    QUEUED,
    /** Confirmed as delivered by the worker. Terminal. */
    DELIVERED,
    /** Could not be delivered. Terminal. */
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
