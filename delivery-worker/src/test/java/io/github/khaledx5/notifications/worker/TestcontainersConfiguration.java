package io.github.khaledx5.notifications.worker;

import io.github.khaledx5.notifications.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Real infrastructure for tests, started once per test context and wired in by Spring Boot. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final int MAILPIT_SMTP_PORT = 1025;
    public static final int MAILPIT_HTTP_PORT = 8025;

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.2"));
    }

    /** Fake SMTP server that keeps every email and lists them over HTTP. */
    @Bean
    GenericContainer<?> mailpitContainer() {
        return new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.33"))
                .withExposedPorts(MAILPIT_SMTP_PORT, MAILPIT_HTTP_PORT);
    }

    @Bean
    DynamicPropertyRegistrar mailProperties(@Qualifier("mailpitContainer") GenericContainer<?> mailpit) {
        return registry -> {
            registry.add("spring.mail.host", mailpit::getHost);
            registry.add("spring.mail.port", () -> mailpit.getMappedPort(MAILPIT_SMTP_PORT));
        };
    }

    /** In production notification-api declares this topic; here the test stands in for it. */
    @Bean
    NewTopic notificationsRequestedTopic() {
        return TopicBuilder.name(Topics.NOTIFICATIONS_REQUESTED)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
