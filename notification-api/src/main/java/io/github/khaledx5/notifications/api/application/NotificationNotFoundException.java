package io.github.khaledx5.notifications.api.application;

import java.util.UUID;

/** Thrown when no notification exists for the requested id. */
public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException(UUID id) {
        super("No notification with id %s".formatted(id));
    }
}
