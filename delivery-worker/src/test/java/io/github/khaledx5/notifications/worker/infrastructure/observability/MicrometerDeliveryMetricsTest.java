package io.github.khaledx5.notifications.worker.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.khaledx5.notifications.events.DeliveryChannel;
import io.github.khaledx5.notifications.worker.application.DeliveryMetrics.Attempt;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MicrometerDeliveryMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerDeliveryMetrics metrics = new MicrometerDeliveryMetrics(registry);

    @Test
    void attemptsAreCountedPerChannelAndResult() {
        metrics.record(DeliveryChannel.EMAIL, Attempt.DELIVERED);
        metrics.record(DeliveryChannel.EMAIL, Attempt.DELIVERED);
        metrics.record(DeliveryChannel.EMAIL, Attempt.RATE_LIMITED);
        metrics.record(DeliveryChannel.SMS, Attempt.DELIVERED);

        assertThat(count("email", "delivered")).isEqualTo(2);
        assertThat(count("email", "rate_limited")).isEqualTo(1);
        assertThat(count("sms", "delivered")).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Attempt.class)
    void everyKindOfAttemptHasALowerCaseTagValue(Attempt attempt) {
        metrics.record(DeliveryChannel.PUSH, attempt);

        assertThat(count("push", attempt.name().toLowerCase(java.util.Locale.ROOT)))
                .isEqualTo(1);
    }

    private double count(String channel, String result) {
        return registry.get("notifications.delivery.attempts")
                .tag("channel", channel)
                .tag("result", result)
                .counter()
                .count();
    }
}
