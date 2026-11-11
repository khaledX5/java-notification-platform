package io.github.khaledx5.notifications.api.infrastructure.kafka;

import io.github.khaledx5.notifications.api.application.EventPublishingException;
import io.github.khaledx5.notifications.api.application.NotificationEventPublisher;
import io.github.khaledx5.notifications.api.application.OutboxEvent;
import io.github.khaledx5.notifications.api.domain.Channel;
import io.github.khaledx5.notifications.events.DeliveryChannel;
import io.github.khaledx5.notifications.events.NotificationRequested;
import io.github.khaledx5.notifications.events.Topics;
import java.time.Duration;
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
 * the event. The event id is the id of the outbox entry, so an entry that is published twice gives
 * two events with the same id.
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
    public void publishRequested(OutboxEvent event) {
        String key = event.notificationId().toString();
        try {
            kafka.send(Topics.NOTIFICATIONS_REQUESTED, key, toContract(event))
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw notPublished(event, ex);
        } catch (ExecutionException | TimeoutException | KafkaException ex) {
            throw notPublished(event, ex);
        }
    }

    private static NotificationRequested toContract(OutboxEvent event) {
        return new NotificationRequested(
                event.id(),
                event.notificationId(),
                event.recipient(),
                toDeliveryChannel(event.channel()),
                event.subject(),
                event.body(),
                event.occurredAt());
    }

    private static DeliveryChannel toDeliveryChannel(Channel channel) {
        return switch (channel) {
            case EMAIL -> DeliveryChannel.EMAIL;
            case SMS -> DeliveryChannel.SMS;
            case PUSH -> DeliveryChannel.PUSH;
        };
    }

    private static EventPublishingException notPublished(OutboxEvent event, Exception cause) {
        return new EventPublishingException(
                "Could not publish the requested event of notification " + event.notificationId(), cause);
    }
}
