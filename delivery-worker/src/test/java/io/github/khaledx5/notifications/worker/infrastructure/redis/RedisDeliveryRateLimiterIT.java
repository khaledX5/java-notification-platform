package io.github.khaledx5.notifications.worker.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.khaledx5.notifications.events.DeliveryChannel;
import io.github.khaledx5.notifications.worker.TestcontainersConfiguration;
import java.time.Duration;
import java.util.List;
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

/**
 * The token bucket script against a real Redis. Each test builds its own limiter, so it can pick a
 * limit that makes the behaviour visible within a second.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisDeliveryRateLimiterIT {

    private static final int CAPACITY = 3;
    private static final Duration NO_REFILL_DURING_THE_TEST = Duration.ofMinutes(10);
    private static final Duration QUICK_REFILL = Duration.ofMillis(300);
    private static final int CONCURRENT_WORKERS = 12;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void recipientGetsABurstUpToTheCapacityAndThenNothing() {
        RedisDeliveryRateLimiter limiter = limiter(CAPACITY, NO_REFILL_DURING_THE_TEST);
        String recipient = uniqueRecipient();

        assertThat(limiter.tryAcquire(DeliveryChannel.EMAIL, recipient)).isTrue();
        assertThat(limiter.tryAcquire(DeliveryChannel.EMAIL, recipient)).isTrue();
        assertThat(limiter.tryAcquire(DeliveryChannel.EMAIL, recipient)).isTrue();
        assertThat(limiter.tryAcquire(DeliveryChannel.EMAIL, recipient)).isFalse();
        assertThat(limiter.tryAcquire(DeliveryChannel.EMAIL, recipient)).isFalse();
    }

    @Test
    void everyRecipientHasItsOwnLimit() {
        RedisDeliveryRateLimiter limiter = limiter(1, NO_REFILL_DURING_THE_TEST);
        String flooded = uniqueRecipient();
        limiter.tryAcquire(DeliveryChannel.EMAIL, flooded);
        assertThat(limiter.tryAcquire(DeliveryChannel.EMAIL, flooded)).isFalse();

        assertThat(limiter.tryAcquire(DeliveryChannel.EMAIL, uniqueRecipient())).isTrue();
    }

    @Test
    void everyChannelOfARecipientHasItsOwnLimit() {
        RedisDeliveryRateLimiter limiter = limiter(1, NO_REFILL_DURING_THE_TEST);
        String recipient = uniqueRecipient();
        limiter.tryAcquire(DeliveryChannel.EMAIL, recipient);
        assertThat(limiter.tryAcquire(DeliveryChannel.EMAIL, recipient)).isFalse();

        assertThat(limiter.tryAcquire(DeliveryChannel.PUSH, recipient)).isTrue();
    }

    @Test
    void usedUpLimitComesBackWithTime() {
        RedisDeliveryRateLimiter limiter = limiter(1, QUICK_REFILL);
        String recipient = uniqueRecipient();
        limiter.tryAcquire(DeliveryChannel.SMS, recipient);
        assertThat(limiter.tryAcquire(DeliveryChannel.SMS, recipient)).isFalse();

        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> limiter.tryAcquire(DeliveryChannel.SMS, recipient));
    }

    @Test
    void waitingLongerThanAFullRefillNeverGivesMoreThanTheCapacity() throws InterruptedException {
        RedisDeliveryRateLimiter limiter = limiter(2, Duration.ofMillis(50));
        String recipient = uniqueRecipient();
        limiter.tryAcquire(DeliveryChannel.SMS, recipient);
        limiter.tryAcquire(DeliveryChannel.SMS, recipient);

        Thread.sleep(500);

        long granted = IntStream.range(0, 6)
                .filter(i -> limiter.tryAcquire(DeliveryChannel.SMS, recipient))
                .count();
        assertThat(granted).isBetween(2L, 3L);
    }

    @Test
    void workersAskingAtTheSameTimeNeverGetMoreThanTheCapacity() throws Exception {
        RedisDeliveryRateLimiter limiter = limiter(CAPACITY, NO_REFILL_DURING_THE_TEST);
        String recipient = uniqueRecipient();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> ask = () -> {
            start.await();
            return limiter.tryAcquire(DeliveryChannel.PUSH, recipient);
        };

        int granted = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_WORKERS)) {
            List<Future<Boolean>> answers = IntStream.range(0, CONCURRENT_WORKERS)
                    .mapToObj(i -> executor.submit(ask))
                    .toList();
            start.countDown();
            for (Future<Boolean> answer : answers) {
                if (answer.get()) {
                    granted++;
                }
            }
        }

        assertThat(granted).isEqualTo(CAPACITY);
    }

    @Test
    void bucketIsForgottenOnceItWouldBeFullAgain() {
        RedisDeliveryRateLimiter limiter = limiter(CAPACITY, Duration.ofSeconds(20));
        String recipient = uniqueRecipient();

        limiter.tryAcquire(DeliveryChannel.EMAIL, recipient);

        assertThat(redis.getExpire(RedisDeliveryRateLimiter.key(DeliveryChannel.EMAIL, recipient)))
                .isBetween(50L, 60L);
    }

    private RedisDeliveryRateLimiter limiter(int capacity, Duration refillInterval) {
        return new RedisDeliveryRateLimiter(redis, new RateLimitProperties(capacity, refillInterval));
    }

    private static String uniqueRecipient() {
        return "dina-" + UUID.randomUUID() + "@example.com";
    }
}
