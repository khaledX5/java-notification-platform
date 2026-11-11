package io.github.khaledx5.notifications.worker.infrastructure.redis;

import io.github.khaledx5.notifications.events.DeliveryChannel;
import io.github.khaledx5.notifications.worker.application.DeliveryRateLimiter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis adapter for {@link DeliveryRateLimiter}: one token bucket per recipient and channel, kept
 * and updated by a Lua script.
 *
 * <p>The bucket lives in Redis so that all worker instances share it. Reading the tokens and taking
 * one has to be a single step, which a script gives and separate commands would not.
 *
 * <p>If Redis cannot be reached the message is let through: a limit that is briefly not enforced
 * does less harm than a worker that delivers nothing.
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
class RedisDeliveryRateLimiter implements DeliveryRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisDeliveryRateLimiter.class);

    static final String KEY_PREFIX = "delivery-worker:rate:";

    private static final RedisScript<Long> TOKEN_BUCKET =
            RedisScript.of(new ClassPathResource("redis/token-bucket.lua"), Long.class);
    private static final long TOKEN_TAKEN = 1L;

    private final StringRedisTemplate redis;
    private final String capacity;
    private final String refillMillis;

    RedisDeliveryRateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.capacity = String.valueOf(properties.capacity());
        this.refillMillis = String.valueOf(properties.refillInterval().toMillis());
    }

    @Override
    public boolean tryAcquire(DeliveryChannel channel, String recipient) {
        try {
            Long result = redis.execute(TOKEN_BUCKET, List.of(key(channel, recipient)), capacity, refillMillis);
            return result != null && result == TOKEN_TAKEN;
        } catch (DataAccessException ex) {
            log.warn("The rate limit for {} could not be checked; letting the message through", channel, ex);
            return true;
        }
    }

    /** The recipient is hashed: email addresses and phone numbers do not belong in key names. */
    static String key(DeliveryChannel channel, String recipient) {
        return KEY_PREFIX + channel + ":" + sha256(recipient);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Every Java runtime has to provide SHA-256", ex);
        }
    }
}
