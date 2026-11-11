package io.github.khaledx5.notifications.worker.infrastructure.redis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for recognising events that arrive more than once.
 *
 * @param ttl how long the result of an event is remembered; it has to outlast the whole retry chain
 *     and any replay of the topic that should not send messages again
 */
@ConfigurationProperties("notifications.delivery.deduplication")
record DeduplicationProperties(@DefaultValue("24h") Duration ttl) {}
