package io.github.khaledx5.notifications.worker.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.khaledx5.notifications.worker.TestcontainersConfiguration;
import io.github.khaledx5.notifications.worker.application.DeliveryResult;
import io.github.khaledx5.notifications.worker.application.ProcessedEvents;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Remembered event results against a real Redis. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisProcessedEventsIT {

    @Autowired
    private ProcessedEvents processedEvents;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void eventThatWasNeverHandledHasNoResult() {
        assertThat(processedEvents.resultOf(UUID.randomUUID())).isEmpty();
    }

    @Test
    void deliveredResultIsRemembered() {
        UUID eventId = UUID.randomUUID();

        processedEvents.remember(eventId, DeliveryResult.delivered());

        assertThat(processedEvents.resultOf(eventId)).contains(DeliveryResult.delivered());
    }

    @Test
    void failedResultIsRememberedWithItsReason() {
        UUID eventId = UUID.randomUUID();
        DeliveryResult failed = DeliveryResult.failed("Invalid address: expected name@host, got 12:30");

        processedEvents.remember(eventId, failed);

        assertThat(processedEvents.resultOf(eventId)).contains(failed);
    }

    @Test
    void rememberedResultExpiresAfterADay() {
        UUID eventId = UUID.randomUUID();

        processedEvents.remember(eventId, DeliveryResult.delivered());

        assertThat(redis.getExpire(RedisProcessedEvents.key(eventId)))
                .isBetween(
                        Duration.ofHours(23).toSeconds(), Duration.ofHours(24).toSeconds());
    }
}
