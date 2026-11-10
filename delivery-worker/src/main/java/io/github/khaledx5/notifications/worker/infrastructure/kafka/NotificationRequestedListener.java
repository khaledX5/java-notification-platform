package io.github.khaledx5.notifications.worker.infrastructure.kafka;

import io.github.khaledx5.notifications.events.NotificationRequested;
import io.github.khaledx5.notifications.events.Topics;
import io.github.khaledx5.notifications.worker.application.DeliveryService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.stereotype.Component;

/**
 * Kafka entry point of the worker.
 *
 * <p>A delivery that fails is not retried in place, which would hold up every event behind it on
 * the partition. The event is moved to a retry topic and read again after a growing delay:
 *
 * <pre>
 * notifications.requested --> -retry-0 --> -retry-1 --> ... --> -dlt
 * </pre>
 *
 * Once the attempts are used up the event lands on the dead-letter topic, where the notification is
 * reported as failed. The event stays on that topic for inspection.
 */
@Component
class NotificationRequestedListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationRequestedListener.class);

    private final DeliveryService deliveries;

    NotificationRequestedListener(DeliveryService deliveries) {
        this.deliveries = deliveries;
    }

    @RetryableTopic(
            attempts = "${notifications.delivery.retry.attempts}",
            backOff =
                    @BackOff(
                            delayString = "${notifications.delivery.retry.delay}",
                            multiplierString = "${notifications.delivery.retry.multiplier}",
                            maxDelayString = "${notifications.delivery.retry.max-delay}"),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            numPartitions = "${notifications.events.topic-partitions}",
            replicationFactor = "${notifications.events.topic-replicas}")
    @KafkaListener(topics = Topics.NOTIFICATIONS_REQUESTED)
    void onRequested(NotificationRequested event) {
        deliveries.deliver(event);
    }

    /** Receives what could not be processed: events that ran out of attempts and unreadable records. */
    @DltHandler
    void onDeadLetter(ConsumerRecord<String, ?> deadLetter) {
        if (deadLetter.value() instanceof NotificationRequested event) {
            deliveries.abandon(event);
            return;
        }
        log.error(
                "Skipping an unreadable record on {} (partition {}, offset {})",
                deadLetter.topic(),
                deadLetter.partition(),
                deadLetter.offset());
    }
}
