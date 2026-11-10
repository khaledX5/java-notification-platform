package io.github.khaledx5.notifications.worker.application;

/**
 * The message can never be delivered as it is, for example because the recipient is not a valid
 * address. Trying again would fail the same way.
 */
public class UndeliverableException extends DeliveryException {

    private static final long serialVersionUID = 1L;

    public UndeliverableException(String message) {
        super(message);
    }

    public UndeliverableException(String message, Throwable cause) {
        super(message, cause);
    }
}
