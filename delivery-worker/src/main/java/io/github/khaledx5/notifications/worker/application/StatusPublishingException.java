package io.github.khaledx5.notifications.worker.application;

/** A delivery result could not be handed to the message broker. */
public class StatusPublishingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StatusPublishingException(String message, Throwable cause) {
        super(message, cause);
    }
}
