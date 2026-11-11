package io.github.khaledx5.notifications.worker.infrastructure.redis;

import io.github.khaledx5.notifications.worker.application.DeliveryResult;
import io.github.khaledx5.notifications.worker.application.ProcessedEvents;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis adapter for {@link ProcessedEvents}: one expiring key per event id, holding its result.
 *
 * <p>If Redis cannot be reached the worker keeps delivering. An event may then be sent twice, which
 * at-least-once delivery allows; stopping all deliveries because the duplicate check is down would be
 * the worse failure.
 */
@Component
@EnableConfigurationProperties(DeduplicationProperties.class)
class RedisProcessedEvents implements ProcessedEvents {

    private static final Logger log = LoggerFactory.getLogger(RedisProcessedEvents.class);

    static final String KEY_PREFIX = "delivery-worker:processed:";

    private static final String DELIVERED = "DELIVERED";
    private static final String FAILED_PREFIX = "FAILED:";

    private final StringRedisTemplate redis;
    private final DeduplicationProperties properties;

    RedisProcessedEvents(StringRedisTemplate redis, DeduplicationProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public Optional<DeliveryResult> resultOf(UUID eventId) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(key(eventId))).flatMap(value -> decode(eventId, value));
        } catch (DataAccessException ex) {
            log.warn("Could not check whether event {} was already handled; handling it", eventId, ex);
            return Optional.empty();
        }
    }

    @Override
    public void remember(UUID eventId, DeliveryResult result) {
        try {
            redis.opsForValue().set(key(eventId), encode(result), properties.ttl());
        } catch (DataAccessException ex) {
            log.warn("Could not remember the result of event {}; it may be handled again", eventId, ex);
        }
    }

    static String key(UUID eventId) {
        return KEY_PREFIX + eventId;
    }

    private static String encode(DeliveryResult result) {
        return result.isDelivered() ? DELIVERED : FAILED_PREFIX + result.failureReason();
    }

    private static Optional<DeliveryResult> decode(UUID eventId, String value) {
        if (value.equals(DELIVERED)) {
            return Optional.of(DeliveryResult.delivered());
        }
        if (value.startsWith(FAILED_PREFIX) && value.length() > FAILED_PREFIX.length()) {
            return Optional.of(DeliveryResult.failed(value.substring(FAILED_PREFIX.length())));
        }
        log.warn("Ignoring an unreadable result stored for event {}", eventId);
        return Optional.empty();
    }
}
