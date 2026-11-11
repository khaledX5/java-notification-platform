package io.github.khaledx5.notifications.api.application;

/** The first request with this idempotency key is still running, so there is no result to repeat yet. */
public class RequestInProgressException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RequestInProgressException() {
        super("A request with this idempotency key is still being processed");
    }
}
