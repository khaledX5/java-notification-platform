package io.github.khaledx5.notifications.api.infrastructure.kafka;

import io.github.khaledx5.notifications.api.application.DeliveryResultRecorder;
import io.github.khaledx5.notifications.events.DeliveryStatusChanged;
import io.github.khaledx5.notifications.events.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Kafka entry point for the delivery results the worker reports. */
@Component
class DeliveryStatusListener {

    private final DeliveryResultRecorder results;

    DeliveryStatusListener(DeliveryResultRecorder results) {
        this.results = results;
    }

    @KafkaListener(topics = Topics.NOTIFICATIONS_STATUS)
    void onStatusChanged(DeliveryStatusChanged event) {
        switch (event.outcome()) {
            case DELIVERED -> results.delivered(event.notificationId());
            case FAILED -> results.failed(event.notificationId(), event.failureReason());
        }
    }
}
