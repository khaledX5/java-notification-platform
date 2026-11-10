package io.github.khaledx5.notifications.api.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.khaledx5.notifications.api.application.EventPublishingException;
import io.github.khaledx5.notifications.api.domain.Channel;
import io.github.khaledx5.notifications.api.domain.Notification;
import io.github.khaledx5.notifications.events.DeliveryChannel;
import io.github.khaledx5.notifications.events.NotificationRequested;
import io.github.khaledx5.notifications.events.Topics;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;

class KafkaNotificationEventPublisherTest {

    private static final Instant NOW = Instant.parse("2026-01-10T09:00:00.123Z");
    private static final Duration SEND_TIMEOUT = Duration.ofMillis(200);

    @SuppressWarnings("unchecked")
    private final KafkaOperations<String, Object> kafka = mock(KafkaOperations.class);

    private final KafkaNotificationEventPublisher publisher =
            new KafkaNotificationEventPublisher(kafka, new KafkaEventsProperties(SEND_TIMEOUT, 3, (short) 1));

    @Test
    void publishesTheNotificationKeyedByItsId() {
        Notification notification = Notification.request(
                "dina@example.com", Channel.EMAIL, "Order shipped", "Your order is on its way", NOW);
        brokerConfirmsEverySend();

        publisher.publishRequested(notification);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(kafka)
                .send(eq(Topics.NOTIFICATIONS_REQUESTED), eq(notification.id().toString()), event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(NotificationRequested.class, requested -> {
            assertThat(requested.eventId()).isNotNull();
            assertThat(requested.notificationId()).isEqualTo(notification.id());
            assertThat(requested.recipient()).isEqualTo("dina@example.com");
            assertThat(requested.channel()).isEqualTo(DeliveryChannel.EMAIL);
            assertThat(requested.subject()).isEqualTo("Order shipped");
            assertThat(requested.body()).isEqualTo("Your order is on its way");
            assertThat(requested.occurredAt()).isEqualTo(NOW);
        });
    }

    @ParameterizedTest
    @EnumSource(Channel.class)
    void everyChannelHasAnEventCounterpart(Channel channel) {
        brokerConfirmsEverySend();

        publisher.publishRequested(Notification.request("recipient-1", channel, null, "Hello", NOW));

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(anyString(), anyString(), event.capture());
        NotificationRequested requested = (NotificationRequested) event.getValue();
        assertThat(requested.channel().name()).isEqualTo(channel.name());
        assertThat(requested.subject()).isNull();
    }

    @Test
    void brokerRejectionIsReportedAsAPublishingFailure() {
        Notification notification = Notification.request("+201000000000", Channel.SMS, null, "Code 4821", NOW);
        given(kafka.send(anyString(), anyString(), any()))
                .willReturn(CompletableFuture.failedFuture(new TimeoutException("broker did not answer")));

        assertThatThrownBy(() -> publisher.publishRequested(notification))
                .isInstanceOf(EventPublishingException.class)
                .hasMessageContaining(notification.id().toString())
                .hasRootCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void failureWhileHandingTheEventToTheClientIsReportedAsAPublishingFailure() {
        Notification notification = Notification.request("+201000000000", Channel.SMS, null, "Code 4821", NOW);
        given(kafka.send(anyString(), anyString(), any())).willThrow(new TimeoutException("no metadata"));

        assertThatThrownBy(() -> publisher.publishRequested(notification)).isInstanceOf(EventPublishingException.class);
    }

    @Test
    void unconfirmedSendGivesUpAfterTheTimeout() {
        Notification notification = Notification.request("+201000000000", Channel.SMS, null, "Code 4821", NOW);
        given(kafka.send(anyString(), anyString(), any())).willReturn(new CompletableFuture<>());

        assertThatThrownBy(() -> publisher.publishRequested(notification))
                .isInstanceOf(EventPublishingException.class)
                .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
    }

    @Test
    void interruptedSendKeepsTheInterruptFlag() {
        Notification notification = Notification.request("+201000000000", Channel.SMS, null, "Code 4821", NOW);
        given(kafka.send(anyString(), anyString(), any())).willReturn(new CompletableFuture<>());

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> publisher.publishRequested(notification))
                    .isInstanceOf(EventPublishingException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private void brokerConfirmsEverySend() {
        SendResult<String, Object> confirmation = new SendResult<>(null, null);
        given(kafka.send(anyString(), anyString(), any())).willReturn(CompletableFuture.completedFuture(confirmation));
    }
}
