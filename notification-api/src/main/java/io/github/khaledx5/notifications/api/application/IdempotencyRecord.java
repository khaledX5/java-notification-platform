package io.github.khaledx5.notifications.api.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What an idempotency key holds.
 *
 * @param fingerprint identifies the content of the request that owns the key
 * @param notificationId the notification that request produced; null while it is still running
 */
public record IdempotencyRecord(String fingerprint, UUID notificationId) {

    public IdempotencyRecord {
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
    }

    public static IdempotencyRecord inProgress(String fingerprint) {
        return new IdempotencyRecord(fingerprint, null);
    }

    public static IdempotencyRecord completed(String fingerprint, UUID notificationId) {
        return new IdempotencyRecord(fingerprint, Objects.requireNonNull(notificationId, "notificationId"));
    }

    /** Whether the key was reserved by a request with this content. */
    public boolean belongsTo(String otherFingerprint) {
        return fingerprint.equals(otherFingerprint);
    }

    /** Empty while the request that owns the key is still running. */
    public Optional<UUID> result() {
        return Optional.ofNullable(notificationId);
    }
}
