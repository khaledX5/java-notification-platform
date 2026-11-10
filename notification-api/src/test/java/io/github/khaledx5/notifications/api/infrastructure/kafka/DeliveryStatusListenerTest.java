package io.github.khaledx5.notifications.api.infrastructure.kafka;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import io.github.khaledx5.notifications.api.application.DeliveryResultRecorder;
import io.github.khaledx5.notifications.events.DeliveryStatusChanged;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliveryStatusListenerTest {

    private static final Instant NOW = Instant.parse("2026-01-10T09:00:05.250Z");
    private static final UUID NOTIFICATION_ID = UUID.fromString("2f9d3c1e-8a47-4b55-b0c8-5e1d7a9f6b22");

    private final DeliveryResultRecorder results = mock(DeliveryResultRecorder.class);
    private final DeliveryStatusListener listener = new DeliveryStatusListener(results);

    @Test
    void deliveredEventIsRecordedAsDelivered() {
        listener.onStatusChanged(DeliveryStatusChanged.delivered(NOTIFICATION_ID, NOW));

        verify(results).delivered(NOTIFICATION_ID);
        verifyNoMoreInteractions(results);
    }

    @Test
    void failedEventIsRecordedWithItsReason() {
        listener.onStatusChanged(DeliveryStatusChanged.failed(NOTIFICATION_ID, "gateway is down", NOW));

        verify(results).failed(NOTIFICATION_ID, "gateway is down");
        verifyNoMoreInteractions(results);
    }
}
