package io.github.khaledx5.notifications.api.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import io.github.khaledx5.notifications.api.application.IdempotencyUnavailableException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Behaviour of the store when Redis misbehaves; the happy path runs against a real Redis. */
@ExtendWith(MockitoExtension.class)
class RedisIdempotencyStoreTest {

    private static final Duration TTL = Duration.ofHours(24);
    private static final Duration IN_PROGRESS_TTL = Duration.ofMinutes(1);
    private static final String KEY = "order-1042-shipped";
    private static final String REDIS_KEY = RedisIdempotencyStore.redisKey(KEY);
    private static final String FINGERPRINT = "3f2a";

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> values;

    private RedisIdempotencyStore store;

    @BeforeEach
    void setUp() {
        store = new RedisIdempotencyStore(redis, new IdempotencyProperties(TTL, IN_PROGRESS_TTL));
    }

    @Test
    void keyCannotBeReservedWhenRedisIsDown() {
        given(redis.opsForValue()).willThrow(new RedisConnectionFailureException("connection refused"));

        assertThatThrownBy(() -> store.reserve(KEY, FINGERPRINT))
                .isInstanceOf(IdempotencyUnavailableException.class)
                .hasCauseInstanceOf(RedisConnectionFailureException.class);
    }

    @Test
    void keyCannotBeCompletedWhenRedisIsDown() {
        given(redis.opsForValue()).willThrow(new RedisConnectionFailureException("connection refused"));

        assertThatThrownBy(() -> store.complete(KEY, FINGERPRINT, UUID.randomUUID()))
                .isInstanceOf(IdempotencyUnavailableException.class);
    }

    @Test
    void keyCannotBeReleasedWhenRedisIsDown() {
        willThrow(new RedisConnectionFailureException("connection refused"))
                .given(redis)
                .delete(REDIS_KEY);

        assertThatThrownBy(() -> store.release(KEY)).isInstanceOf(IdempotencyUnavailableException.class);
    }

    @Test
    void keyThatExpiresRightAfterItWasFoundTakenIsReservedOnTheNextTry() {
        given(redis.opsForValue()).willReturn(values);
        given(values.setIfAbsent(REDIS_KEY, FINGERPRINT, IN_PROGRESS_TTL)).willReturn(false, true);
        given(values.get(REDIS_KEY)).willReturn(null);

        assertThat(store.reserve(KEY, FINGERPRINT)).isEmpty();
    }

    @Test
    void keyThatNeverSettlesIsReportedAsUnavailableInsteadOfLoopingForever() {
        given(redis.opsForValue()).willReturn(values);
        given(values.setIfAbsent(REDIS_KEY, FINGERPRINT, IN_PROGRESS_TTL)).willReturn(false);
        given(values.get(REDIS_KEY)).willReturn(null);

        assertThatThrownBy(() -> store.reserve(KEY, FINGERPRINT)).isInstanceOf(IdempotencyUnavailableException.class);
    }
}
