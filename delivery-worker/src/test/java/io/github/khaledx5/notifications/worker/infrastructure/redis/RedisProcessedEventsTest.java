package io.github.khaledx5.notifications.worker.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;

import io.github.khaledx5.notifications.worker.application.DeliveryResult;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Behaviour of the duplicate check when Redis misbehaves; the happy path runs against a real Redis. */
@ExtendWith(MockitoExtension.class)
class RedisProcessedEventsTest {

    private static final UUID EVENT_ID = UUID.fromString("4f0c7f0e-5a55-4c0b-9a57-0d2f6f1f3a10");

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> values;

    private RedisProcessedEvents processedEvents;

    @BeforeEach
    void setUp() {
        processedEvents = new RedisProcessedEvents(redis, new DeduplicationProperties(Duration.ofHours(24)));
    }

    @Test
    void eventCountsAsNewWhenRedisIsDown() {
        given(redis.opsForValue()).willThrow(new RedisConnectionFailureException("connection refused"));

        assertThat(processedEvents.resultOf(EVENT_ID)).isEmpty();
    }

    @Test
    void resultThatCannotBeRememberedDoesNotFailTheDelivery() {
        given(redis.opsForValue()).willThrow(new RedisConnectionFailureException("connection refused"));

        assertThatCode(() -> processedEvents.remember(EVENT_ID, DeliveryResult.delivered()))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "SENT", "FAILED", "FAILED:"})
    void unreadableStoredResultCountsAsNew(String stored) {
        given(redis.opsForValue()).willReturn(values);
        given(values.get(RedisProcessedEvents.key(EVENT_ID))).willReturn(stored);

        assertThat(processedEvents.resultOf(EVENT_ID)).isEmpty();
    }
}
