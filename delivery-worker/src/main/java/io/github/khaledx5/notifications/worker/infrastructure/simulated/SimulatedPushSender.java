package io.github.khaledx5.notifications.worker.infrastructure.simulated;

import io.github.khaledx5.notifications.events.DeliveryChannel;
import io.github.khaledx5.notifications.worker.application.ChannelSender;
import io.github.khaledx5.notifications.worker.application.OutboundMessage;
import io.github.khaledx5.notifications.worker.application.UndeliverableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stands in for a push provider: it checks the device token and logs the hand-over instead of
 * calling a real push service. A real provider client replaces this class without touching the rest
 * of the worker.
 */
@Component
class SimulatedPushSender implements ChannelSender {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPushSender.class);

    @Override
    public DeliveryChannel channel() {
        return DeliveryChannel.PUSH;
    }

    @Override
    public void send(OutboundMessage message) {
        if (message.recipient().isBlank() || message.recipient().chars().anyMatch(Character::isWhitespace)) {
            throw new UndeliverableException("The recipient is not a device token");
        }
        // The device token and the text stay out of the log: both are personal data.
        log.info("Push provider accepted notification {}", message.notificationId());
    }
}
