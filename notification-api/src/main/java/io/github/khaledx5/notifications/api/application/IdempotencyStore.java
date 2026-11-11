package io.github.khaledx5.notifications.api.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Outgoing port that remembers which request an idempotency key belongs to.
 *
 * <p>A key is first reserved for the request that is about to run, and completed once that request
 * has produced a notification. A reservation that is never completed expires on its own.
 */
public interface IdempotencyStore {

    /**
     * Reserves the key for a request, unless the key is already taken. Check and reservation are one
     * atomic step, so two requests with the same key can never both get it.
     *
     * @param fingerprint identifies the content of the request
     * @return empty if the key was free and now belongs to this request; otherwise what the key holds
     * @throws IdempotencyUnavailableException if the store cannot be reached
     */
    Optional<IdempotencyRecord> reserve(String key, String fingerprint);

    /**
     * Records the notification the reserved request produced. From now on the key answers repeated
     * requests with that notification.
     *
     * @throws IdempotencyUnavailableException if the store cannot be reached
     */
    void complete(String key, String fingerprint, UUID notificationId);

    /**
     * Frees a reserved key whose request failed, so the client can send the request again.
     *
     * @throws IdempotencyUnavailableException if the store cannot be reached
     */
    void release(String key);
}
