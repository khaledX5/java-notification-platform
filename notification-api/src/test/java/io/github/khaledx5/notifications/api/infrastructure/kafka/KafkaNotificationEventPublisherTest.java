package io.github.khaledx5.notifications.api.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.khaledx5.notifications.api.application.EventPublishingException;
import io.github.khaledx5.notifications.api.application.OutboxEvent;
import io.github.khaledx5.notifications.api.domain.Channel;
import io.github.khaledx5.notifications.events.DeliveryChannel;
import io.github.khaledx5.notifications.events.NotificationRequested;
import io.github.khaledx5.notifications.events.Topics;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
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
    void publishesTheEventKeyedByItsNotificationId() {
        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "dina@example.com",
                Channel.EMAIL,
                "Order shipped",
                "Your order is on its way",
                NOW);
        brokerConfirmsEverySend();

        publisher.publishRequested(event);

        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(kafka)
                .send(
                        eq(Topics.NOTIFICATIONS_REQUESTED),
                        eq(event.notificationId().toString()),
                        sent.capture());
        assertThat(sent.getValue()).isInstanceOfSatisfying(NotificationRequested.class, requested -> {
            assertThat(requested.eventId()).isEqualTo(event.id());
            assertThat(requested.notificationId()).isEqualTo(event.notificationId());
            assertThat(requested.recipient()).isEqualTo("dina@example.com");
            assertThat(requested.channel()).isEqualTo(DeliveryChannel.EMAIL);
            assertThat(requested.subject()).isEqualTo("Order shipped");
            assertThat(requested.body()).isEqualTo("Your order is on its way");
            assertThat(requested.occurredAt()).isEqualTo(NOW);
        });
    }

    @Test
    void eventThatIsPublishedTwiceKeepsItsEventId() {
        OutboxEvent event = sms();
        brokerConfirmsEverySend();

        publisher.publishRequested(event);
        publisher.publishRequested(event);

        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(kafka, times(2)).send(anyString(), anyString(), sent.capture());
        assertThat(sent.getAllValues())
                .extracting(value -> ((NotificationRequested) value).eventId())
                .containsExactly(event.id(), event.id());
    }

    @ParameterizedTest
    @EnumSource(Channel.class)
    void everyChannelHasAnEventCounterpart(Channel channel) {
        brokerConfirmsEverySend();

        publisher.publishRequested(
                new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "recipient-1", channel, null, "Hello", NOW));

        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(anyString(), anyString(), sent.capture());
        NotificationRequested requested = (NotificationRequested) sent.getValue();
        assertThat(requested.channel().name()).isEqualTo(channel.name());
        assertThat(requested.subject()).isNull();
    }

    @Test
    void brokerRejectionIsReportedAsAPublishingFailure() {
        OutboxEvent event = sms();
        given(kafka.send(anyString(), anyString(), any()))
                .willReturn(CompletableFuture.failedFuture(new TimeoutException("broker did not answer")));

        assertThatThrownBy(() -> publisher.publishRequested(event))
                .isInstanceOf(EventPublishingException.class)
                .hasMessageContaining(event.notificationId().toString())
                .hasRootCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void failureWhileHandingTheEventToTheClientIsReportedAsAPublishingFailure() {
        given(kafka.send(anyString(), anyString(), any())).willThrow(new TimeoutException("no metadata"));

        assertThatThrownBy(() -> publisher.publishRequested(sms())).isInstanceOf(EventPublishingException.class);
    }

    @Test
    void unconfirmedSendGivesUpAfterTheTimeout() {
        given(kafka.send(anyString(), anyString(), any())).willReturn(new CompletableFuture<>());

        assertThatThrownBy(() -> publisher.publishRequested(sms()))
                .isInstanceOf(EventPublishingException.class)
                .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
    }

    @Test
    void interruptedSendKeepsTheInterruptFlag() {
        given(kafka.send(anyString(), anyString(), any())).willReturn(new CompletableFuture<>());

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> publisher.publishRequested(sms())).isInstanceOf(EventPublishingException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static OutboxEvent sms() {
        return new OutboxEvent(
                UUID.randomUUID(), UUID.randomUUID(), "+201000000000", Channel.SMS, null, "Code 4821", NOW);
    }

    private void brokerConfirmsEverySend() {
        SendResult<String, Object> confirmation = new SendResult<>(null, null);
        given(kafka.send(anyString(), anyString(), any())).willReturn(CompletableFuture.completedFuture(confirmation));
    }
}
