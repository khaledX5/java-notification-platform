package io.github.khaledx5.notifications.api.application;

/** An event could not be handed to the message broker. */
public class EventPublishingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EventPublishingException(String message, Throwable cause) {
        super(message, cause);
    }
}
