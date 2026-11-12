package io.github.khaledx5.notifications.worker.infrastructure.observability;

import io.github.khaledx5.notifications.events.DeliveryChannel;
import io.github.khaledx5.notifications.worker.application.DeliveryMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Micrometer adapter for {@link DeliveryMetrics}: one counter, tagged with the channel and how the
 * attempt ended. Both tags come from small fixed sets, so the number of time series stays bounded.
 */
@Component
class MicrometerDeliveryMetrics implements DeliveryMetrics {

    static final String DELIVERY_ATTEMPTS = "notifications.delivery.attempts";

    private final MeterRegistry registry;

    MicrometerDeliveryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void record(DeliveryChannel channel, Attempt attempt) {
        Counter.builder(DELIVERY_ATTEMPTS)
                .description("Attempts to handle a request event, by channel and by how they ended")
                .tag("channel", lowerCase(channel))
                .tag("result", lowerCase(attempt))
                .register(registry)
                .increment();
    }

    private static String lowerCase(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
