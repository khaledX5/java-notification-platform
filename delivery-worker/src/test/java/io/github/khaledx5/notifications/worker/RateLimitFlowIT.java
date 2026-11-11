package io.github.khaledx5.notifications.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.khaledx5.notifications.events.DeliveryChannel;
import io.github.khaledx5.notifications.events.DeliveryOutcome;
import io.github.khaledx5.notifications.events.DeliveryStatusChanged;
import io.github.khaledx5.notifications.events.NotificationRequested;
import io.github.khaledx5.notifications.events.Topics;
import io.github.khaledx5.notifications.worker.application.ChannelSender;
import io.github.khaledx5.notifications.worker.application.DeliveryRateLimiter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The rate limit end to end, against a real broker and a real Redis.
 *
 * <p>The limit is small and refills fast here: a recipient gets two messages at once and earns one
 * back every second, and a postponed message is retried every 500ms. A burst of three therefore
 * shows the whole behaviour within about two seconds.
 */
@SpringBootTest(
        properties = {
            "notifications.delivery.rate-limit.capacity=2",
            "notifications.delivery.rate-limit.refill-interval=1s",
            "notifications.delivery.retry.attempts=6",
            "notifications.delivery.retry.delay=500ms",
            "notifications.delivery.retry.multiplier=1",
            "notifications.delivery.retry.max-delay=1s"
        })
@Import({TestcontainersConfiguration.class, RateLimitFlowIT.StatusEventsConfiguration.class})
class RateLimitFlowIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int CAPACITY = 2;

    @Autowired
    private KafkaTemplate<String, Object> kafka;

    @Autowired
    private StatusEvents statuses;

    @MockitoSpyBean(name = "simulatedPushSender")
    private ChannelSender push;

    @MockitoSpyBean
    private DeliveryRateLimiter rateLimiter;

    @Test
    void messageOverTheLimitIsPostponedAndDeliveredOnceTheLimitAllowsIt() {
        String token = "device-" + UUID.randomUUID();
        List<NotificationRequested> burst = IntStream.rangeClosed(1, CAPACITY + 1)
                .mapToObj(i -> requested(token, "Message " + i))
                .toList();

        burst.forEach(this::publish);

        for (NotificationRequested event : burst) {
            assertThat(statuses.awaitFor(event.notificationId()).outcome()).isEqualTo(DeliveryOutcome.DELIVERED);
        }
        // Every message reached the provider exactly once: an attempt that was held back sent nothing.
        verify(push, times(CAPACITY + 1))
                .send(argThat(message -> message.recipient().equals(token)));
        // At least one attempt was refused, so the limiter was asked more often than there are messages.
        verify(rateLimiter, atLeast(CAPACITY + 2)).tryAcquire(eq(DeliveryChannel.PUSH), eq(token));
    }

    @Test
    void recipientOverTheLimitDoesNotHoldBackOtherRecipients() {
        String flooded = "device-" + UUID.randomUUID();
        IntStream.rangeClosed(1, CAPACITY + 1)
                .mapToObj(i -> requested(flooded, "Message " + i))
                .forEach(this::publish);
        NotificationRequested other = requested("device-" + UUID.randomUUID(), "Hello");

        publish(other);

        assertThat(statuses.awaitFor(other.notificationId()).outcome()).isEqualTo(DeliveryOutcome.DELIVERED);
        verify(rateLimiter, times(1)).tryAcquire(eq(DeliveryChannel.PUSH), eq(other.recipient()));
    }

    private void publish(NotificationRequested event) {
        kafka.send(Topics.NOTIFICATIONS_REQUESTED, event.notificationId().toString(), event)
                .join();
    }

    private static NotificationRequested requested(String token, String body) {
        return new NotificationRequested(
                UUID.randomUUID(), UUID.randomUUID(), token, DeliveryChannel.PUSH, null, body, Instant.now());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StatusEventsConfiguration {

        @Bean
        StatusEvents statusEvents() {
            return new StatusEvents();
        }
    }

    /** Plays the part of notification-api: collects the status events the worker publishes. */
    static class StatusEvents {

        private final Map<UUID, DeliveryStatusChanged> byNotification = new ConcurrentHashMap<>();

        @KafkaListener(
                topics = Topics.NOTIFICATIONS_STATUS,
                groupId = "rate-limit-flow-it",
                properties =
                        "spring.json.value.default.type=io.github.khaledx5.notifications.events.DeliveryStatusChanged")
        void onStatusChanged(DeliveryStatusChanged event) {
            byNotification.put(event.notificationId(), event);
        }

        DeliveryStatusChanged awaitFor(UUID notificationId) {
            await().atMost(TIMEOUT).until(() -> byNotification.containsKey(notificationId));
            return byNotification.get(notificationId);
        }
    }
}
