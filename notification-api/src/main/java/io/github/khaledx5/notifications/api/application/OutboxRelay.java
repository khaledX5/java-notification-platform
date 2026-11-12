package io.github.khaledx5.notifications.api.application;

import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Moves events from the outbox to the broker.
 *
 * <p>An event is marked as published only after the broker confirmed it. If the relay stops between
 * the two steps the event is published again later, so events are delivered at least once and carry
 * a stable id for consumers to recognise the repeat.
 *
 * <p>Each event is published as a continuation of the trace of the request that caused it.
 */
@Service
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final Outbox outbox;
    private final NotificationEventPublisher events;
    private final TracePropagation tracing;
    private final NotificationMetrics metrics;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxRelay(
            Outbox outbox,
            NotificationEventPublisher events,
            TracePropagation tracing,
            NotificationMetrics metrics,
            OutboxProperties properties,
            Clock clock) {
        this.outbox = outbox;
        this.events = events;
        this.tracing = tracing;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Publishes waiting events, oldest first, until the outbox is empty or the batch is full.
     *
     * <p>The run stops at the first event the broker does not take: the broker is most likely down,
     * and the events behind it would only fail the same way. That event keeps its claim and is tried
     * again once the claim has run out.
     *
     * @return how many events were published
     */
    public int relayPending() {
        int published = 0;
        while (published < properties.batchSize()) {
            Optional<OutboxEvent> next = outbox.claimNext(clock.instant(), properties.claimDuration());
            if (next.isEmpty()) {
                break;
            }
            OutboxEvent event = next.get();
            try {
                tracing.resume(event.trace(), () -> events.publishRequested(event));
            } catch (EventPublishingException ex) {
                metrics.eventPublishFailed();
                log.warn(
                        "Event {} of notification {} could not be published; it stays in the outbox",
                        event.id(),
                        event.notificationId(),
                        ex);
                break;
            }
            outbox.markPublished(event.id(), clock.instant());
            metrics.eventPublished();
            published++;
        }
        return published;
    }
}
