package io.github.khaledx5.notifications.worker.infrastructure.simulated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.khaledx5.notifications.events.DeliveryChannel;
import io.github.khaledx5.notifications.worker.application.DeliveryException;
import io.github.khaledx5.notifications.worker.application.OutboundMessage;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SimulatedSendersTest {

    private final SimulatedSmsSender sms = new SimulatedSmsSender();
    private final SimulatedPushSender push = new SimulatedPushSender();

    @Test
    void eachSenderHandlesItsOwnChannel() {
        assertThat(sms.channel()).isEqualTo(DeliveryChannel.SMS);
        assertThat(push.channel()).isEqualTo(DeliveryChannel.PUSH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"+201000000000", "+14155550123", "+4915112345678"})
    void smsToAnE164NumberIsAccepted(String number) {
        assertThatCode(() -> sms.send(message(number))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"01000000000", "+0123456789", "+20 100 000 0000", "dina@example.com", "+12345", ""})
    void smsToAnythingElseIsRejected(String recipient) {
        assertThatThrownBy(() -> sms.send(message(recipient)))
                .isInstanceOf(DeliveryException.class)
                .hasMessageContaining("E.164");
    }

    @Test
    void pushToADeviceTokenIsAccepted() {
        assertThatCode(() -> push.send(message("fcm-token-9f8a7b6c"))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "two tokens"})
    void pushWithoutAUsableTokenIsRejected(String recipient) {
        assertThatThrownBy(() -> push.send(message(recipient)))
                .isInstanceOf(DeliveryException.class)
                .hasMessageContaining("device token");
    }

    private static OutboundMessage message(String recipient) {
        return new OutboundMessage(UUID.randomUUID(), recipient, null, "Hello");
    }
}
