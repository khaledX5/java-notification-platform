package io.github.khaledx5.notifications.api.infrastructure.kafka;

import io.github.khaledx5.notifications.api.application.EventPublishingException;
import io.github.khaledx5.notifications.api.application.NotificationEventPublisher;
import io.github.khaledx5.notifications.api.domain.Channel;
import io.github.khaledx5.notifications.api.domain.Notification;
import io.github.khaledx5.notifications.events.DeliveryChannel;
import io.github.khaledx5.notifications.events.NotificationRequested;
import io.github.khaledx5.notifications.events.Topics;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.common.KafkaException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.stereotype.Component;

/**
 * Kafka adapter for {@link NotificationEventPublisher}.
 *
 * <p>Events are keyed by notification id, so everything about one notification lands on the same
 * partition and keeps its order. The send is awaited: the caller only continues once the broker has
 * the event.
 */
@Component
class KafkaNotificationEventPublisher implements NotificationEventPublisher {

    private final KafkaOperations<String, Object> kafka;
    private final Duration sendTimeout;

    KafkaNotificationEventPublisher(KafkaOperations<String, Object> kafka, KafkaEventsProperties properties) {
        this.kafka = kafka;
        this.sendTimeout = properties.sendTimeout();
    }

    @Override
    public void publishRequested(Notification notification) {
        String key = notification.id().toString();
        try {
            kafka.send(Topics.NOTIFICATIONS_REQUESTED, key, toEvent(notification))
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw notPublished(notification, ex);
        } catch (ExecutionException | TimeoutException | KafkaException ex) {
            throw notPublished(notification, ex);
        }
    }

    private static NotificationRequested toEvent(Notification notification) {
        return new NotificationRequested(
                UUID.randomUUID(),
                notification.id(),
                notification.recipient(),
                toDeliveryChannel(notification.channel()),
                notification.subject().orElse(null),
                notification.body(),
                notification.updatedAt());
    }

    private static DeliveryChannel toDeliveryChannel(Channel channel) {
        return switch (channel) {
            case EMAIL -> DeliveryChannel.EMAIL;
            case SMS -> DeliveryChannel.SMS;
            case PUSH -> DeliveryChannel.PUSH;
        };
    }

    private static EventPublishingException notPublished(Notification notification, Exception cause) {
        return new EventPublishingException(
                "Could not publish the requested event of notification " + notification.id(), cause);
    }
}
