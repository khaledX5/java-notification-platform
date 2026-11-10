package io.github.khaledx5.notifications.worker.infrastructure.kafka;

import io.github.khaledx5.notifications.events.DeliveryStatusChanged;
import io.github.khaledx5.notifications.events.Topics;
import io.github.khaledx5.notifications.worker.application.DeliveryStatusPublisher;
import io.github.khaledx5.notifications.worker.application.StatusPublishingException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.common.KafkaException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.stereotype.Component;

/**
 * Kafka adapter for {@link DeliveryStatusPublisher}.
 *
 * <p>Status events use the notification id as their key, like the request events, so the API reads
 * everything about one notification in order. The send is awaited: the offset of the request is
 * only committed after its result is on the broker.
 */
@Component
class KafkaDeliveryStatusPublisher implements DeliveryStatusPublisher {

    private final KafkaOperations<String, Object> kafka;
    private final Duration sendTimeout;
    private final Clock clock;

    KafkaDeliveryStatusPublisher(KafkaOperations<String, Object> kafka, KafkaEventsProperties properties, Clock clock) {
        this.kafka = kafka;
        this.sendTimeout = properties.sendTimeout();
        this.clock = clock;
    }

    @Override
    public void delivered(UUID notificationId) {
        publish(DeliveryStatusChanged.delivered(notificationId, clock.instant()));
    }

    @Override
    public void failed(UUID notificationId, String reason) {
        publish(DeliveryStatusChanged.failed(notificationId, reason, clock.instant()));
    }

    private void publish(DeliveryStatusChanged event) {
        String key = event.notificationId().toString();
        try {
            kafka.send(Topics.NOTIFICATIONS_STATUS, key, event).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw notPublished(event, ex);
        } catch (ExecutionException | TimeoutException | KafkaException ex) {
            throw notPublished(event, ex);
        }
    }

    private static StatusPublishingException notPublished(DeliveryStatusChanged event, Exception cause) {
        return new StatusPublishingException(
                "Could not publish the " + event.outcome() + " status of notification " + event.notificationId(),
                cause);
    }
}
