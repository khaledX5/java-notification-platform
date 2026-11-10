package io.github.khaledx5.notifications.worker.infrastructure.kafka;

import io.github.khaledx5.notifications.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topic this service writes to. The broker does not create topics on first use, so the
 * producing side owns the declaration and it is applied at startup.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaEventsProperties.class)
class KafkaTopicConfiguration {

    @Bean
    NewTopic notificationsStatusTopic(KafkaEventsProperties properties) {
        return TopicBuilder.name(Topics.NOTIFICATIONS_STATUS)
                .partitions(properties.topicPartitions())
                .replicas(properties.topicReplicas())
                .build();
    }
}
