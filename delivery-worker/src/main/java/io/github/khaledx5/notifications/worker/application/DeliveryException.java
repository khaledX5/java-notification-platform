package io.github.khaledx5.notifications.worker.application;

/** A message could not be handed to its channel's provider. */
public class DeliveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DeliveryException(String message) {
        super(message);
    }

    public DeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
