package io.github.khaledx5.notifications.api.infrastructure.redis;

import io.github.khaledx5.notifications.api.application.IdempotencyRecord;
import io.github.khaledx5.notifications.api.application.IdempotencyStore;
import io.github.khaledx5.notifications.api.application.IdempotencyUnavailableException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis adapter for {@link IdempotencyStore}.
 *
 * <p>A key is reserved with {@code SET NX}, which only one of several concurrent requests can win.
 * The value is the request fingerprint while the request runs, and {@code fingerprint:notificationId}
 * once it is done.
 *
 * <p>Unlike the template cache, this is not an optimisation: if Redis cannot be reached the request is
 * refused, because accepting it could create the duplicate the client asked to be protected from.
 */
@Component
@EnableConfigurationProperties(IdempotencyProperties.class)
class RedisIdempotencyStore implements IdempotencyStore {

    static final String KEY_PREFIX = "notification-api:idempotency:";

    /** Never part of a fingerprint (hex digits) and never part of a UUID. */
    private static final char SEPARATOR = ':';

    /** A key can expire between the failed reservation and the read that follows; then it is tried again. */
    private static final int RESERVE_ATTEMPTS = 3;

    private final StringRedisTemplate redis;
    private final IdempotencyProperties properties;

    RedisIdempotencyStore(StringRedisTemplate redis, IdempotencyProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public Optional<IdempotencyRecord> reserve(String key, String fingerprint) {
        String redisKey = redisKey(key);
        try {
            for (int attempt = 0; attempt < RESERVE_ATTEMPTS; attempt++) {
                Boolean reserved = redis.opsForValue().setIfAbsent(redisKey, fingerprint, properties.inProgressTtl());
                if (Boolean.TRUE.equals(reserved)) {
                    return Optional.empty();
                }
                String held = redis.opsForValue().get(redisKey);
                if (held != null) {
                    return Optional.of(decode(held));
                }
            }
        } catch (DataAccessException ex) {
            throw new IdempotencyUnavailableException("Could not reserve an idempotency key", ex);
        }
        throw new IdempotencyUnavailableException("An idempotency key kept changing while it was being reserved", null);
    }

    @Override
    public void complete(String key, String fingerprint, UUID notificationId) {
        try {
            redis.opsForValue().set(redisKey(key), fingerprint + SEPARATOR + notificationId, properties.ttl());
        } catch (DataAccessException ex) {
            throw new IdempotencyUnavailableException("Could not complete an idempotency key", ex);
        }
    }

    @Override
    public void release(String key) {
        try {
            redis.delete(redisKey(key));
        } catch (DataAccessException ex) {
            throw new IdempotencyUnavailableException("Could not release an idempotency key", ex);
        }
    }

    static String redisKey(String key) {
        return KEY_PREFIX + key;
    }

    private static IdempotencyRecord decode(String value) {
        int separator = value.indexOf(SEPARATOR);
        if (separator < 0) {
            return IdempotencyRecord.inProgress(value);
        }
        return IdempotencyRecord.completed(
                value.substring(0, separator), UUID.fromString(value.substring(separator + 1)));
    }
}
