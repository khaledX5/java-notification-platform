package io.github.khaledx5.notifications.api.infrastructure.observability;

import io.github.khaledx5.notifications.api.application.NotificationMetrics;
import io.github.khaledx5.notifications.api.application.Outbox;
import io.github.khaledx5.notifications.api.domain.Channel;
import io.github.khaledx5.notifications.api.domain.NotificationStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Micrometer adapter for {@link NotificationMetrics}.
 *
 * <p>Tags only take values from small fixed sets (channel, result, status). An id or a recipient as a
 * tag would create one time series per value and overload whatever stores the metrics.
 */
@Component
class MicrometerNotificationMetrics implements NotificationMetrics {

    static final String ACCEPTED = "notifications.accepted";
    static final String REPLAYS = "notifications.idempotent.replays";
    static final String OUTBOX_EVENTS = "notifications.outbox.events";
    static final String OUTBOX_WAITING = "notifications.outbox.waiting";
    static final String COMPLETED = "notifications.completed";

    private final MeterRegistry registry;

    MicrometerNotificationMetrics(MeterRegistry registry, Outbox outbox) {
        this.registry = registry;
        Gauge.builder(OUTBOX_WAITING, outbox, Outbox::countWaiting)
                .description("Events in the outbox that have not been published yet; it should stay close to zero")
                .register(registry);
    }

    @Override
    public void accepted(Channel channel) {
        Counter.builder(ACCEPTED)
                .description("Notifications accepted by the API")
                .tag("channel", lowerCase(channel))
                .register(registry)
                .increment();
    }

    @Override
    public void replayed() {
        Counter.builder(REPLAYS)
                .description("Repeated requests answered with the notification of the first request")
                .register(registry)
                .increment();
    }

    @Override
    public void eventPublished() {
        outboxEvent("published");
    }

    @Override
    public void eventPublishFailed() {
        outboxEvent("failed");
    }

    @Override
    public void completed(NotificationStatus finalStatus) {
        Counter.builder(COMPLETED)
                .description("Notifications that reached a final status")
                .tag("status", lowerCase(finalStatus))
                .register(registry)
                .increment();
    }

    private void outboxEvent(String result) {
        Counter.builder(OUTBOX_EVENTS)
                .description("Attempts to publish an outbox event to the broker")
                .tag("result", result)
                .register(registry)
                .increment();
    }

    private static String lowerCase(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
