package io.github.khaledx5.notifications.worker.infrastructure.kafka;

import io.github.khaledx5.notifications.events.NotificationRequested;
import io.github.khaledx5.notifications.events.Topics;
import io.github.khaledx5.notifications.worker.application.DeliveryService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Kafka entry point of the worker: every requested notification is handed to the delivery service. */
@Component
class NotificationRequestedListener {

    private final DeliveryService deliveries;

    NotificationRequestedListener(DeliveryService deliveries) {
        this.deliveries = deliveries;
    }

    @KafkaListener(topics = Topics.NOTIFICATIONS_REQUESTED)
    void onRequested(NotificationRequested event) {
        deliveries.deliver(event);
    }
}
