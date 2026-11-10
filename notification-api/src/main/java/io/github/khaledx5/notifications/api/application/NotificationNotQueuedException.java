package io.github.khaledx5.notifications.api.application;

import java.util.UUID;

/** A notification was accepted but could not be queued for delivery; it is stored as failed. */
public class NotificationNotQueuedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID notificationId;

    public NotificationNotQueuedException(UUID notificationId, Throwable cause) {
        super("Notification " + notificationId + " could not be queued for delivery", cause);
        this.notificationId = notificationId;
    }

    public UUID notificationId() {
        return notificationId;
    }
}
