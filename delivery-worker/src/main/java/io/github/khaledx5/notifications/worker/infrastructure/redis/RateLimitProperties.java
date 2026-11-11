package io.github.khaledx5.notifications.worker.infrastructure.redis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limit on the messages one recipient gets over one channel, as a token bucket.
 *
 * @param capacity most messages a recipient can get at once
 * @param refillInterval time to earn one message back; with 6s a recipient gets 10 per minute once the
 *     burst is used up
 */
@ConfigurationProperties("notifications.delivery.rate-limit")
record RateLimitProperties(
        @DefaultValue("10") int capacity,
        @DefaultValue("6s") Duration refillInterval) {

    RateLimitProperties {
        if (capacity < 1) {
            throw new IllegalArgumentException("notifications.delivery.rate-limit.capacity must be at least 1");
        }
        if (refillInterval.toMillis() < 1) {
            throw new IllegalArgumentException(
                    "notifications.delivery.rate-limit.refill-interval must be at least 1ms");
        }
    }
}
