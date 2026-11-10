package io.github.khaledx5.notifications.worker.infrastructure.mail;

import io.github.khaledx5.notifications.events.DeliveryChannel;
import io.github.khaledx5.notifications.worker.application.ChannelSender;
import io.github.khaledx5.notifications.worker.application.DeliveryException;
import io.github.khaledx5.notifications.worker.application.OutboundMessage;
import io.github.khaledx5.notifications.worker.application.UndeliverableException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
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
        } catch (MailException ex) {
            throw new DeliveryException("The SMTP server did not accept the email", ex);
        }
    }
}
