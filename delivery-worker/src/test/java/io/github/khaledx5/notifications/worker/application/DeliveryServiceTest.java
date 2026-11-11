package io.github.khaledx5.notifications.worker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.khaledx5.notifications.events.DeliveryChannel;
import io.github.khaledx5.notifications.events.NotificationRequested;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DeliveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-10T09:00:00.123Z");

    private final RecordingSender email = new RecordingSender(DeliveryChannel.EMAIL);
    private final RecordingSender sms = new RecordingSender(DeliveryChannel.SMS);
    private final RecordingSender push = new RecordingSender(DeliveryChannel.PUSH);
    private final RecordingStatusPublisher statuses = new RecordingStatusPublisher();
    private final InMemoryProcessedEvents processedEvents = new InMemoryProcessedEvents();
    private final DeliveryService service = new DeliveryService(List.of(email, sms, push), statuses, processedEvents);

    @ParameterizedTest
    @EnumSource(DeliveryChannel.class)
    void eventIsDeliveredOnlyThroughTheSenderOfItsChannel(DeliveryChannel channel) {
        NotificationRequested event = requested(channel, "Hello");

        service.deliver(event);

        for (RecordingSender sender : List.of(email, sms, push)) {
            if (sender.channel() == channel) {
                assertThat(sender.sent).hasSize(1);
            } else {
                assertThat(sender.sent).isEmpty();
            }
        }
    }

    @Test
    void senderGetsTheContentOfTheEvent() {
        NotificationRequested event = new NotificationRequested(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "dina@example.com",
                DeliveryChannel.EMAIL,
                "Order shipped",
                "Your order is on its way",
                NOW);

        service.deliver(event);

        assertThat(email.sent)
                .containsExactly(new OutboundMessage(
                        event.notificationId(), "dina@example.com", "Order shipped", "Your order is on its way"));
    }

    @Test
    void deliveredNotificationIsReportedAsDelivered() {
        NotificationRequested event = requested(DeliveryChannel.PUSH, "You have a new message");

        service.deliver(event);

        assertThat(statuses.delivered).containsExactly(event.notificationId());
        assertThat(statuses.failed).isEmpty();
    }

    @Test
    void notificationThatCanNeverBeDeliveredIsReportedAsFailedWithTheReason() {
        sms.failWith(new UndeliverableException("The recipient is not an E.164 phone number"));
        NotificationRequested event = requested(DeliveryChannel.SMS, "Code 4821");

        service.deliver(event);

        assertThat(statuses.failed)
                .containsExactly(Map.entry(event.notificationId(), "The recipient is not an E.164 phone number"));
        assertThat(statuses.delivered).isEmpty();
    }

    @Test
    void failureThatMayPassIsPassedOnWithoutAReportSoTheEventIsRetried() {
        sms.failWith(new DeliveryException("gateway is down"));

        assertThatThrownBy(() -> service.deliver(requested(DeliveryChannel.SMS, "Code 4821")))
                .isInstanceOf(DeliveryException.class)
                .hasMessage("gateway is down");

        assertThat(statuses.failed).isEmpty();
        assertThat(statuses.delivered).isEmpty();
    }

    @Test
    void abandonedNotificationIsReportedAsFailed() {
        NotificationRequested event = requested(DeliveryChannel.EMAIL, "Hello");

        service.abandon(event);

        assertThat(statuses.failed)
                .containsExactly(Map.entry(event.notificationId(), DeliveryService.ABANDONED_REASON));
        assertThat(email.sent).isEmpty();
    }

    @Test
    void resultThatCannotBeReportedIsPassedOnSoTheEventIsProcessedAgain() {
        statuses.failWith(new StatusPublishingException("broker is down", new IllegalStateException()));

        assertThatThrownBy(() -> service.deliver(requested(DeliveryChannel.EMAIL, "Hello")))
                .isInstanceOf(StatusPublishingException.class);
    }

    @Test
    void eventThatArrivesAgainIsNotSentTwiceButItsReportIsRepeated() {
        NotificationRequested event = requested(DeliveryChannel.EMAIL, "Hello");

        service.deliver(event);
        service.deliver(event);

        assertThat(email.sent).hasSize(1);
        assertThat(statuses.delivered).containsExactly(event.notificationId(), event.notificationId());
    }

    @Test
    void failedResultIsRepeatedTooWhenTheEventArrivesAgain() {
        sms.failWith(new UndeliverableException("The recipient is not an E.164 phone number"));
        NotificationRequested event = requested(DeliveryChannel.SMS, "Code 4821");
        service.deliver(event);
        sms.failWith(null);

        service.deliver(event);

        assertThat(sms.sent).isEmpty();
        assertThat(statuses.delivered).isEmpty();
        assertThat(statuses.failed)
                .hasSize(2)
                .containsOnly(Map.entry(event.notificationId(), "The recipient is not an E.164 phone number"));
    }

    @Test
    void eventWhoseReportWasLostIsReportedOnTheRetryWithoutBeingSentAgain() {
        NotificationRequested event = requested(DeliveryChannel.EMAIL, "Hello");
        statuses.failWith(new StatusPublishingException("broker is down", new IllegalStateException()));
        assertThatThrownBy(() -> service.deliver(event)).isInstanceOf(StatusPublishingException.class);
        statuses.failWith(null);

        service.deliver(event);

        assertThat(email.sent).hasSize(1);
        assertThat(statuses.delivered).containsExactly(event.notificationId());
    }

    @Test
    void failureThatMayPassIsNotRememberedSoTheRetryIsSent() {
        NotificationRequested event = requested(DeliveryChannel.PUSH, "You have a new message");
        push.failWith(new DeliveryException("The push provider is down"));
        assertThatThrownBy(() -> service.deliver(event)).isInstanceOf(DeliveryException.class);
        push.failWith(null);

        service.deliver(event);

        assertThat(push.sent).hasSize(1);
        assertThat(statuses.delivered).containsExactly(event.notificationId());
    }

    @Test
    void twoEventsForTheSameNotificationAreBothDelivered() {
        UUID notificationId = UUID.randomUUID();
        NotificationRequested first = new NotificationRequested(
                UUID.randomUUID(), notificationId, "device-1", DeliveryChannel.PUSH, null, "Hello", NOW);
        NotificationRequested second = new NotificationRequested(
                UUID.randomUUID(), notificationId, "device-1", DeliveryChannel.PUSH, null, "Hello", NOW);

        service.deliver(first);
        service.deliver(second);

        assertThat(push.sent).hasSize(2);
    }

    @Test
    void abandonedEventStaysFailedIfItArrivesAgain() {
        NotificationRequested event = requested(DeliveryChannel.EMAIL, "Hello");
        service.abandon(event);

        service.deliver(event);

        assertThat(email.sent).isEmpty();
        assertThat(statuses.failed)
                .hasSize(2)
                .containsOnly(Map.entry(event.notificationId(), DeliveryService.ABANDONED_REASON));
    }

    @Test
    void eventThatWasDeliveredButCouldNotBeReportedIsNotAbandonedAsFailed() {
        NotificationRequested event = requested(DeliveryChannel.EMAIL, "Hello");
        statuses.failWith(new StatusPublishingException("broker is down", new IllegalStateException()));
        assertThatThrownBy(() -> service.deliver(event)).isInstanceOf(StatusPublishingException.class);
        statuses.failWith(null);

        service.abandon(event);

        assertThat(statuses.delivered).containsExactly(event.notificationId());
        assertThat(statuses.failed).isEmpty();
    }

    @Test
    void channelWithoutASenderStopsTheWorkerAtStartup() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new DeliveryService(List.of(email, sms), statuses, processedEvents))
                .withMessageContaining("PUSH");
    }

    @Test
    void twoSendersForOneChannelStopTheWorkerAtStartup() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new DeliveryService(
                        List.of(email, sms, push, new RecordingSender(DeliveryChannel.SMS)), statuses, processedEvents))
                .withMessageContaining("SMS");
    }

    private static NotificationRequested requested(DeliveryChannel channel, String body) {
        return new NotificationRequested(UUID.randomUUID(), UUID.randomUUID(), "recipient-1", channel, null, body, NOW);
    }

    private static final class InMemoryProcessedEvents implements ProcessedEvents {

        private final Map<UUID, DeliveryResult> results = new HashMap<>();

        @Override
        public Optional<DeliveryResult> resultOf(UUID eventId) {
            return Optional.ofNullable(results.get(eventId));
        }

        @Override
        public void remember(UUID eventId, DeliveryResult result) {
            results.put(eventId, result);
        }
    }

    private static final class RecordingStatusPublisher implements DeliveryStatusPublisher {

        private final List<UUID> delivered = new ArrayList<>();
        private final List<Map.Entry<UUID, String>> failed = new ArrayList<>();
        private StatusPublishingException failure;

        void failWith(StatusPublishingException failure) {
            this.failure = failure;
        }

        @Override
        public void delivered(UUID notificationId) {
            if (failure != null) {
                throw failure;
            }
            delivered.add(notificationId);
        }

        @Override
        public void failed(UUID notificationId, String reason) {
            if (failure != null) {
                throw failure;
            }
            failed.add(Map.entry(notificationId, reason));
        }
    }

    private static final class RecordingSender implements ChannelSender {

        private final DeliveryChannel channel;
        private final List<OutboundMessage> sent = new ArrayList<>();
        private DeliveryException failure;

        private RecordingSender(DeliveryChannel channel) {
            this.channel = channel;
        }

        void failWith(DeliveryException failure) {
            this.failure = failure;
        }

        @Override
        public DeliveryChannel channel() {
            return channel;
        }

        @Override
        public void send(OutboundMessage message) {
            if (failure != null) {
                throw failure;
            }
            sent.add(message);
        }
    }
}
