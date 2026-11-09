package io.github.khaledx5.notifications.api.domain;

import java.util.UUID;

/** Thrown when a notification is asked to move to a status its lifecycle does not allow. */
public class IllegalStatusTransitionException extends RuntimeException {

    private final NotificationStatus from;
    private final NotificationStatus to;

    public IllegalStatusTransitionException(UUID notificationId, NotificationStatus from, NotificationStatus to) {
        super("Notification %s cannot move from %s to %s".formatted(notificationId, from, to));
        this.from = from;
        this.to = to;
    }

    public NotificationStatus from() {
        return from;
    }

    public NotificationStatus to() {
        return to;
    }
}
