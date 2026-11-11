package io.github.khaledx5.notifications.api.application;

/** An idempotency key was sent again with a request whose content differs from the first one. */
public class IdempotencyKeyReusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IdempotencyKeyReusedException() {
        super("The idempotency key was already used with a different request");
    }
}
