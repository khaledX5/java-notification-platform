package io.github.khaledx5.notifications.worker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.khaledx5.notifications.events.DeliveryChannel;
import io.github.khaledx5.notifications.events.NotificationRequested;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DeliveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-10T09:00:00.123Z");

    private final RecordingSender email = new RecordingSender(DeliveryChannel.EMAIL);
    private final RecordingSender sms = new RecordingSender(DeliveryChannel.SMS);
    private final RecordingSender push = new RecordingSender(DeliveryChannel.PUSH);
    private final DeliveryService service = new DeliveryService(List.of(email, sms, push));

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
    void failureOfTheSenderIsPassedOn() {
        sms.failWith(new DeliveryException("gateway is down"));

        assertThatThrownBy(() -> service.deliver(requested(DeliveryChannel.SMS, "Code 4821")))
                .isInstanceOf(DeliveryException.class)
                .hasMessage("gateway is down");
    }

    @Test
    void channelWithoutASenderStopsTheWorkerAtStartup() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new DeliveryService(List.of(email, sms)))
                .withMessageContaining("PUSH");
    }

    @Test
    void twoSendersForOneChannelStopTheWorkerAtStartup() {
        assertThatIllegalStateException()
                .isThrownBy(
                        () -> new DeliveryService(List.of(email, sms, push, new RecordingSender(DeliveryChannel.SMS))))
                .withMessageContaining("SMS");
    }

    private static NotificationRequested requested(DeliveryChannel channel, String body) {
        return new NotificationRequested(UUID.randomUUID(), UUID.randomUUID(), "recipient-1", channel, null, body, NOW);
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
