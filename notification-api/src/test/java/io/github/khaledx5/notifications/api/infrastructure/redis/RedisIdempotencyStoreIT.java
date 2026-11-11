package io.github.khaledx5.notifications.api.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.khaledx5.notifications.api.TestcontainersConfiguration;
import io.github.khaledx5.notifications.api.application.IdempotencyRecord;
import io.github.khaledx5.notifications.api.application.IdempotencyStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Idempotency keys against a real Redis: reservation, completion, release and expiry. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisIdempotencyStoreIT {

    private static final String FINGERPRINT = "3f2a";
    private static final int CONCURRENT_REQUESTS = 16;

    @Autowired
    private IdempotencyStore store;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void freeKeyIsReservedForAShortTime() {
        String key = uniqueKey();

        assertThat(store.reserve(key, FINGERPRINT)).isEmpty();

        assertThat(redis.getExpire(RedisIdempotencyStore.redisKey(key)))
                .isBetween(1L, Duration.ofMinutes(1).toSeconds());
    }

    @Test
    void reservedKeyReportsTheRequestThatIsStillRunning() {
        String key = uniqueKey();
        store.reserve(key, FINGERPRINT);

        assertThat(store.reserve(key, "9c41")).contains(IdempotencyRecord.inProgress(FINGERPRINT));
    }

    @Test
    void completedKeyReportsItsNotificationAndIsKeptForADay() {
        String key = uniqueKey();
        UUID notificationId = UUID.randomUUID();
        store.reserve(key, FINGERPRINT);

        store.complete(key, FINGERPRINT, notificationId);

        assertThat(store.reserve(key, FINGERPRINT)).contains(IdempotencyRecord.completed(FINGERPRINT, notificationId));
        assertThat(redis.getExpire(RedisIdempotencyStore.redisKey(key)))
                .isBetween(
                        Duration.ofHours(23).toSeconds(), Duration.ofHours(24).toSeconds());
    }

    @Test
    void releasedKeyCanBeReservedAgain() {
        String key = uniqueKey();
        store.reserve(key, FINGERPRINT);

        store.release(key);

        assertThat(store.reserve(key, "9c41")).isEmpty();
    }

    @Test
    void onlyOneOfManyConcurrentRequestsGetsTheKey() throws Exception {
        String key = uniqueKey();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Optional<IdempotencyRecord>> reserve = () -> {
            start.await();
            return store.reserve(key, FINGERPRINT);
        };

        try (ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS)) {
            List<Future<Optional<IdempotencyRecord>>> attempts = IntStream.range(0, CONCURRENT_REQUESTS)
                    .mapToObj(i -> executor.submit(reserve))
                    .toList();
            start.countDown();

            int winners = 0;
            for (Future<Optional<IdempotencyRecord>> attempt : attempts) {
                Optional<IdempotencyRecord> held = attempt.get();
                if (held.isEmpty()) {
                    winners++;
                } else {
                    assertThat(held).contains(IdempotencyRecord.inProgress(FINGERPRINT));
                }
            }
            assertThat(winners).isEqualTo(1);
        }
    }

    private static String uniqueKey() {
        return "it-" + UUID.randomUUID();
    }
}
