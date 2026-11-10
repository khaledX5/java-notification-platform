package io.github.khaledx5.notifications.worker.infrastructure.simulated;

import io.github.khaledx5.notifications.events.DeliveryChannel;
import io.github.khaledx5.notifications.worker.application.ChannelSender;
import io.github.khaledx5.notifications.worker.application.DeliveryException;
import io.github.khaledx5.notifications.worker.application.OutboundMessage;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stands in for an SMS gateway: it checks the phone number the way a gateway would and logs the
 * hand-over instead of calling a paid provider. A real gateway client replaces this class without
 * touching the rest of the worker.
 */
@Component
class SimulatedSmsSender implements ChannelSender {

    private static final Logger log = LoggerFactory.getLogger(SimulatedSmsSender.class);

    /** E.164: a plus sign, then 7 to 15 digits that do not start with zero. */
    private static final Pattern E164_NUMBER = Pattern.compile("\\+[1-9]\\d{6,14}");

    @Override
    public DeliveryChannel channel() {
        return DeliveryChannel.SMS;
    }

    @Override
    public void send(OutboundMessage message) {
        if (!E164_NUMBER.matcher(message.recipient()).matches()) {
            throw new DeliveryException("The recipient is not an E.164 phone number");
        }
        // The text and the number stay out of the log: both are personal data.
        log.info(
                "SMS gateway accepted notification {} ({} characters)",
                message.notificationId(),
                message.body().length());
    }
}
