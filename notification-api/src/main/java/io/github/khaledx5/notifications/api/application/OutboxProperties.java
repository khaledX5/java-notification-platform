package io.github.khaledx5.notifications.api.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for publishing the events that wait in the outbox.
 *
 * @param pollInterval pause between two runs of the relay; also the longest an event normally waits
 * @param batchSize most events published in one run, so one run cannot go on forever
 * @param claimDuration how long a relay keeps an event to itself while publishing it. It has to be
 *     longer than the slowest publish, and it is also the wait before a failed publish is tried again
 */
@ConfigurationProperties("notifications.outbox")
public record OutboxProperties(
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("100") int batchSize,
        @DefaultValue("30s") Duration claimDuration) {

    public OutboxProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException("notifications.outbox.batch-size must be at least 1");
        }
    }
}
