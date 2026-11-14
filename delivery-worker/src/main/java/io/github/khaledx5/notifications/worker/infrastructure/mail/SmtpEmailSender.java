package io.github.khaledx5.notifications.worker.infrastructure.mail;

import io.github.khaledx5.notifications.events.DeliveryChannel;
import io.github.khaledx5.notifications.worker.application.ChannelSender;
import io.github.khaledx5.notifications.worker.application.DeliveryException;
import io.github.khaledx5.notifications.worker.application.OutboundMessage;
import io.github.khaledx5.notifications.worker.application.UndeliverableException;
import jakarta.mail.SendFailedException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Sends plain-text email through the configured SMTP server. */
@Component
@EnableConfigurationProperties(EmailProperties.class)
class SmtpEmailSender implements ChannelSender {

    private final JavaMailSender mail;
    private final String from;

    SmtpEmailSender(JavaMailSender mail, EmailProperties properties) {
        this.mail = mail;
        this.from = properties.from();
    }

    @Override
    public DeliveryChannel channel() {
        return DeliveryChannel.EMAIL;
    }

    @Override
    public void send(OutboundMessage message) {
        SimpleMailMessage email = new SimpleMailMessage();
        email.setFrom(from);
        email.setTo(message.recipient());
        message.optionalSubject().ifPresent(email::setSubject);
        email.setText(message.body());
        try {
            mail.send(email);
        } catch (MailParseException ex) {
            throw new UndeliverableException("The recipient is not a valid email address", ex);
        } catch (MailSendException ex) {
            if (recipientWasRefused(ex)) {
                throw new UndeliverableException("The email server refused the recipient address", ex);
            }
            throw new DeliveryException("The SMTP server did not accept the email", ex);
        } catch (MailException ex) {
            throw new DeliveryException("The SMTP server did not accept the email", ex);
        }
    }

    /**
     * The server answered the recipient with a permanent error, such as 550 (no such mailbox) or 553
     * (not a valid address). Jakarta Mail lists such a recipient as an invalid address; a temporary
     * refusal (4xx) is listed as valid but unsent instead, and is worth trying again.
     */
    private static boolean recipientWasRefused(MailSendException ex) {
        return ex.getFailedMessages().values().stream()
                .anyMatch(failure -> failure instanceof SendFailedException refused
                        && refused.getInvalidAddresses() != null
                        && refused.getInvalidAddresses().length > 0);
    }
}
