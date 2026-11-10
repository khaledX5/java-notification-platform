package io.github.khaledx5.notifications.worker.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.khaledx5.notifications.events.DeliveryChannel;
import io.github.khaledx5.notifications.events.NotificationRequested;
import io.github.khaledx5.notifications.events.Topics;
import io.github.khaledx5.notifications.worker.application.DeliveryException;
import io.github.khaledx5.notifications.worker.application.DeliveryService;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class NotificationRequestedListenerTest {

    private static final String DEAD_LETTER_TOPIC = Topics.NOTIFICATIONS_REQUESTED + "-dlt";

    private final DeliveryService deliveries = mock(DeliveryService.class);
    private final NotificationRequestedListener listener = new NotificationRequestedListener(deliveries);

    @Test
    void requestedEventIsDelivered() {
        NotificationRequested event = requested();

        listener.onRequested(event);

        verify(deliveries).deliver(event);
    }

    @Test
    void deliveryFailureLeavesTheListenerSoTheEventIsRetried() {
        NotificationRequested event = requested();
        willThrow(new DeliveryException("gateway is down")).given(deliveries).deliver(event);

        assertThatThrownBy(() -> listener.onRequested(event)).isInstanceOf(DeliveryException.class);
    }

    @Test
    void deadLetteredEventIsAbandoned() {
        NotificationRequested event = requested();

        listener.onDeadLetter(new ConsumerRecord<>(
                DEAD_LETTER_TOPIC, 0, 7L, event.notificationId().toString(), event));

        verify(deliveries).abandon(event);
    }

    @Test
    void unreadableDeadLetterIsSkipped() {
        listener.onDeadLetter(new ConsumerRecord<>(DEAD_LETTER_TOPIC, 0, 8L, "some-key", null));

        verifyNoInteractions(deliveries);
    }

    private static NotificationRequested requested() {
        return new NotificationRequested(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "+201000000000",
                DeliveryChannel.SMS,
                null,
                "Code 4821",
                Instant.parse("2026-01-10T09:00:00.123Z"));
    }
}
