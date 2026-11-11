package io.github.khaledx5.notifications.api.application;

/** The idempotency store could not be reached, so a key could not be checked or changed. */
public class IdempotencyUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IdempotencyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
