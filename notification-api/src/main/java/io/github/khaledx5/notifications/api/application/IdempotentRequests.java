package io.github.khaledx5.notifications.api.application;

import io.github.khaledx5.notifications.api.domain.Notification;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Makes a notification request safe to send more than once.
 *
 * <p>A client that got no answer cannot know whether its request arrived. It sends the request again
 * with the same idempotency key, and gets the notification of the first request back instead of a
 * second notification.
 *
 * <p>The key is reserved before the request runs and completed after it. If the service stops between
 * storing the notification and completing the key, the reservation expires and a later repeat creates
 * a second notification. Closing that last gap would need the key in the same database write as the
 * notification.
 */
@Service
public class IdempotentRequests {

    private static final Logger log = LoggerFactory.getLogger(IdempotentRequests.class);

    private final IdempotencyStore store;
    private final NotificationService notifications;

    public IdempotentRequests(IdempotencyStore store, NotificationService notifications) {
        this.store = store;
        this.notifications = notifications;
    }

    /**
     * Runs the request unless this key has already produced a notification.
     *
     * @param fingerprint identifies the content of the request
     * @param request creates the notification; only called if the key is new
     * @throws IdempotencyKeyReusedException if the key belongs to a request with different content
     * @throws RequestInProgressException if the first request with this key has not finished yet
     * @throws IdempotencyUnavailableException if the key could not be checked; the request did not run
     */
    public Result execute(String key, String fingerprint, Supplier<Notification> request) {
        Optional<IdempotencyRecord> earlier = store.reserve(key, fingerprint);
        if (earlier.isPresent()) {
            return new Result(repeatOf(earlier.get(), fingerprint), true);
        }
        Notification notification = runReserved(key, request);
        complete(key, fingerprint, notification);
        return new Result(notification, false);
    }

    private Notification repeatOf(IdempotencyRecord earlier, String fingerprint) {
        if (!earlier.belongsTo(fingerprint)) {
            throw new IdempotencyKeyReusedException();
        }
        return earlier.result().map(notifications::getById).orElseThrow(RequestInProgressException::new);
    }

    private Notification runReserved(String key, Supplier<Notification> request) {
        try {
            return request.get();
        } catch (RuntimeException ex) {
            release(key);
            throw ex;
        }
    }

    /** The notification exists at this point, so a store failure must not fail the request. */
    private void complete(String key, String fingerprint, Notification notification) {
        try {
            store.complete(key, fingerprint, notification.id());
        } catch (IdempotencyUnavailableException ex) {
            log.warn(
                    "Notification {} was accepted but its idempotency key could not be completed;"
                            + " a repeated request may create a second notification",
                    notification.id(),
                    ex);
        }
    }

    /** The failure of the request is what the caller needs to see, not a failed clean-up. */
    private void release(String key) {
        try {
            store.release(key);
        } catch (IdempotencyUnavailableException ex) {
            log.warn("A failed request could not release its idempotency key; it stays reserved until it expires", ex);
        }
    }

    /**
     * @param notification the notification the key stands for, as it is now
     * @param replayed true if an earlier request with this key had already created it
     */
    public record Result(Notification notification, boolean replayed) {}
}
