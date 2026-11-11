package io.github.khaledx5.notifications.api.infrastructure.kafka;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the events this service publishes.
 *
 * @param sendTimeout how long the outbox relay waits for the broker to confirm an event
 * @param topicPartitions partitions of the topics this service declares
 * @param topicReplicas replicas of the topics this service declares; one is enough for a single
 *     local broker
 */
@ConfigurationProperties("notifications.events")
record KafkaEventsProperties(
        @DefaultValue("5s") Duration sendTimeout,
        @DefaultValue("3") int topicPartitions,
        @DefaultValue("1") short topicReplicas) {}
