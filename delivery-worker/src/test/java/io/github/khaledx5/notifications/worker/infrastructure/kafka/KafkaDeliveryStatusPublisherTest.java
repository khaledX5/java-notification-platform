package io.github.khaledx5.notifications.worker.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.khaledx5.notifications.events.DeliveryOutcome;
import io.github.khaledx5.notifications.events.DeliveryStatusChanged;
import io.github.khaledx5.notifications.events.Topics;
import io.github.khaledx5.notifications.worker.application.StatusPublishingException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;

class KafkaDeliveryStatusPublisherTest {

    private static final Instant NOW = Instant.parse("2026-01-10T09:00:05.250Z");
    private static final UUID NOTIFICATION_ID = UUID.fromString("2f9d3c1e-8a47-4b55-b0c8-5e1d7a9f6b22");

    @SuppressWarnings("unchecked")
    private final KafkaOperations<String, Object> kafka = mock(KafkaOperations.class);

    private final KafkaDeliveryStatusPublisher publisher = new KafkaDeliveryStatusPublisher(
            kafka, new KafkaEventsProperties(Duration.ofMillis(200), 3, (short) 1), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void deliveredStatusIsPublishedKeyedByTheNotificationId() {
        brokerConfirmsEverySend();

        publisher.delivered(NOTIFICATION_ID);

        DeliveryStatusChanged event = publishedEvent();
        assertThat(event.notificationId()).isEqualTo(NOTIFICATION_ID);
        assertThat(event.outcome()).isEqualTo(DeliveryOutcome.DELIVERED);
        assertThat(event.failureReason()).isNull();
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.eventId()).isNotNull();
    }

    @Test
    void failedStatusCarriesTheReason() {
        brokerConfirmsEverySend();

        publisher.failed(NOTIFICATION_ID, "The SMTP server did not accept the email");

        DeliveryStatusChanged event = publishedEvent();
        assertThat(event.outcome()).isEqualTo(DeliveryOutcome.FAILED);
        assertThat(event.failureReason()).isEqualTo("The SMTP server did not accept the email");
    }

    @Test
    void brokerRejectionIsReportedAsAPublishingFailure() {
        given(kafka.send(anyString(), anyString(), any()))
                .willReturn(CompletableFuture.failedFuture(new TimeoutException("broker did not answer")));

        assertThatThrownBy(() -> publisher.delivered(NOTIFICATION_ID))
                .isInstanceOf(StatusPublishingException.class)
                .hasMessageContaining(NOTIFICATION_ID.toString())
                .hasRootCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void unconfirmedSendGivesUpAfterTheTimeout() {
        given(kafka.send(anyString(), anyString(), any())).willReturn(new CompletableFuture<>());

        assertThatThrownBy(() -> publisher.failed(NOTIFICATION_ID, "gateway is down"))
                .isInstanceOf(StatusPublishingException.class)
                .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
    }

    @Test
    void interruptedSendKeepsTheInterruptFlag() {
        given(kafka.send(anyString(), anyString(), any())).willReturn(new CompletableFuture<>());

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> publisher.delivered(NOTIFICATION_ID))
                    .isInstanceOf(StatusPublishingException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private DeliveryStatusChanged publishedEvent() {
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq(Topics.NOTIFICATIONS_STATUS), eq(NOTIFICATION_ID.toString()), event.capture());
        return (DeliveryStatusChanged) event.getValue();
    }

    private void brokerConfirmsEverySend() {
        SendResult<String, Object> confirmation = new SendResult<>(null, null);
        given(kafka.send(anyString(), anyString(), any())).willReturn(CompletableFuture.completedFuture(confirmation));
    }
}
