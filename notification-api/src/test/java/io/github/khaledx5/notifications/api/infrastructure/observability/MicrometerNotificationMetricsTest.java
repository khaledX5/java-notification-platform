package io.github.khaledx5.notifications.api.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import io.github.khaledx5.notifications.api.application.Outbox;
import io.github.khaledx5.notifications.api.domain.Channel;
import io.github.khaledx5.notifications.api.domain.NotificationStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MicrometerNotificationMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final Outbox outbox = mock(Outbox.class);
    private final MicrometerNotificationMetrics metrics = new MicrometerNotificationMetrics(registry, outbox);

    @Test
    void acceptedNotificationsAreCountedPerChannel() {
        metrics.accepted(Channel.EMAIL);
        metrics.accepted(Channel.EMAIL);
        metrics.accepted(Channel.SMS);

        assertThat(count("notifications.accepted", "channel", "email")).isEqualTo(2);
        assertThat(count("notifications.accepted", "channel", "sms")).isEqualTo(1);
    }

    @Test
    void replaysAreCounted() {
        metrics.replayed();

        assertThat(registry.get("notifications.idempotent.replays").counter().count())
                .isEqualTo(1);
    }

    @Test
    void outboxEventsAreCountedByResult() {
        metrics.eventPublished();
        metrics.eventPublished();
        metrics.eventPublishFailed();

        assertThat(count("notifications.outbox.events", "result", "published")).isEqualTo(2);
        assertThat(count("notifications.outbox.events", "result", "failed")).isEqualTo(1);
    }

    @Test
    void completedNotificationsAreCountedByFinalStatus() {
        metrics.completed(NotificationStatus.DELIVERED);
        metrics.completed(NotificationStatus.FAILED);
        metrics.completed(NotificationStatus.FAILED);

        assertThat(count("notifications.completed", "status", "delivered")).isEqualTo(1);
        assertThat(count("notifications.completed", "status", "failed")).isEqualTo(2);
    }

    @Test
    void waitingOutboxEventsAreReadFromTheOutboxEveryTimeTheGaugeIsRead() {
        given(outbox.countWaiting()).willReturn(3L, 0L);

        assertThat(registry.get("notifications.outbox.waiting").gauge().value()).isEqualTo(3);
        assertThat(registry.get("notifications.outbox.waiting").gauge().value()).isZero();
    }

    private double count(String name, String tag, String value) {
        return registry.get(name).tag(tag, value).counter().count();
    }
}
