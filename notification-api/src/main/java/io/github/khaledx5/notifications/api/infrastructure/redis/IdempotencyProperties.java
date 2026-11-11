package io.github.khaledx5.notifications.api.infrastructure.redis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for idempotency keys kept in Redis.
 *
 * @param ttl how long a repeated request with the same key gets the first notification back
 * @param inProgressTtl how long a key stays reserved if its first request never finishes; it has to be
 *     longer than the slowest request
 */
@ConfigurationProperties("notifications.idempotency")
record IdempotencyProperties(
        @DefaultValue("24h") Duration ttl,
        @DefaultValue("1m") Duration inProgressTtl) {}
