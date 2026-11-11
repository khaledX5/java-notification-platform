package io.github.khaledx5.notifications.worker.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import io.github.khaledx5.notifications.events.DeliveryChannel;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** What the limiter asks of Redis and what it does when Redis misbehaves; the script runs in the IT. */
class RedisDeliveryRateLimiterTest {

    private static final String RECIPIENT = "dina@example.com";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisDeliveryRateLimiter limiter =
            new RedisDeliveryRateLimiter(redis, new RateLimitProperties(10, Duration.ofSeconds(6)));

    @Test
    void scriptGetsTheBucketOfTheRecipientWithTheConfiguredLimit() {
        given(redis.execute(anyScript(), anyList(), any(Object[].class))).willReturn(1L);

        assertThat(limiter.tryAcquire(DeliveryChannel.EMAIL, RECIPIENT)).isTrue();

        then(redis)
                .should()
                .execute(
                        anyScript(),
                        eq(List.of(RedisDeliveryRateLimiter.key(DeliveryChannel.EMAIL, RECIPIENT))),
                        eq("10"),
                        eq("6000"));
    }

    /** A typed matcher, so the generic {@code execute} overload is chosen without an unchecked call. */
    private static RedisScript<Long> anyScript() {
        return any();
    }

    @Test
    void emptyBucketMeansNoPermission() {
        given(redis.execute(anyScript(), anyList(), any(Object[].class))).willReturn(0L);

        assertThat(limiter.tryAcquire(DeliveryChannel.EMAIL, RECIPIENT)).isFalse();
    }

    @Test
    void messageIsLetThroughWhenRedisIsDown() {
        given(redis.execute(anyScript(), anyList(), any(Object[].class)))
                .willThrow(new RedisConnectionFailureException("connection refused"));

        assertThat(limiter.tryAcquire(DeliveryChannel.EMAIL, RECIPIENT)).isTrue();
    }

    @Test
    void bucketKeyDoesNotContainTheRecipient() {
        String key = RedisDeliveryRateLimiter.key(DeliveryChannel.EMAIL, RECIPIENT);

        assertThat(key).startsWith("delivery-worker:rate:EMAIL:").doesNotContain("dina", "example.com");
        assertThat(key.substring("delivery-worker:rate:EMAIL:".length())).matches("[0-9a-f]{64}");
    }

    @Test
    void sameRecipientHasOneBucketPerChannel() {
        assertThat(RedisDeliveryRateLimiter.key(DeliveryChannel.EMAIL, RECIPIENT))
                .isEqualTo(RedisDeliveryRateLimiter.key(DeliveryChannel.EMAIL, RECIPIENT))
                .isNotEqualTo(RedisDeliveryRateLimiter.key(DeliveryChannel.PUSH, RECIPIENT));
    }

    @Test
    void limitWithoutCapacityOrRefillIsRejectedAtStartup() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitProperties(0, Duration.ofSeconds(6)));
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitProperties(10, Duration.ZERO));
    }
}
