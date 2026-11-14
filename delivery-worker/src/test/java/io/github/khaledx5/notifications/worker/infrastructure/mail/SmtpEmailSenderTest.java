package io.github.khaledx5.notifications.worker.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.khaledx5.notifications.events.DeliveryChannel;
import io.github.khaledx5.notifications.worker.application.DeliveryException;
import io.github.khaledx5.notifications.worker.application.OutboundMessage;
import io.github.khaledx5.notifications.worker.application.UndeliverableException;
import jakarta.mail.Address;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpEmailSenderTest {

    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final SmtpEmailSender sender = new SmtpEmailSender(mail, new EmailProperties("no-reply@shop.example"));

    @Test
    void handlesTheEmailChannel() {
        assertThat(sender.channel()).isEqualTo(DeliveryChannel.EMAIL);
    }

    @Test
    void sendsAPlainTextEmailFromTheConfiguredAddress() {
        sender.send(new OutboundMessage(
                UUID.randomUUID(), "dina@example.com", "Order shipped", "Your order is on its way"));

        SimpleMailMessage email = sentEmail();
        assertThat(email.getFrom()).isEqualTo("no-reply@shop.example");
        assertThat(email.getTo()).containsExactly("dina@example.com");
        assertThat(email.getSubject()).isEqualTo("Order shipped");
        assertThat(email.getText()).isEqualTo("Your order is on its way");
    }

    @Test
    void emailWithoutASubjectIsStillSent() {
        sender.send(new OutboundMessage(UUID.randomUUID(), "dina@example.com", null, "Your code is 4821"));

        assertThat(sentEmail().getSubject()).isNull();
    }

    @Test
    void malformedAddressCanNeverBeDelivered() {
        willThrow(new MailParseException("Illegal address")).given(mail).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> sender.send(new OutboundMessage(UUID.randomUUID(), "not an address", "Hi", "Hello")))
                .isInstanceOf(UndeliverableException.class)
                .hasMessageContaining("not a valid email address");
    }

    @Test
    void addressTheServerRefusesForGoodCanNeverBeDelivered() throws AddressException {
        Address[] none = new Address[0];
        Address[] refused = {new InternetAddress("dina@example.com")};
        MailSendException refusal = new MailSendException(Map.of(
                new SimpleMailMessage(), new SendFailedException("Invalid Addresses", null, none, none, refused)));
        willThrow(refusal).given(mail).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> sender.send(new OutboundMessage(UUID.randomUUID(), "dina@example.com", "Hi", "Hello")))
                .isInstanceOf(UndeliverableException.class)
                .hasMessage("The email server refused the recipient address")
                .hasCause(refusal);
    }

    @Test
    void addressTheServerRefusesForNowIsWorthRetrying() throws AddressException {
        Address[] none = new Address[0];
        Address[] notSentYet = {new InternetAddress("dina@example.com")};
        MailSendException busy = new MailSendException(
                Map.of(new SimpleMailMessage(), new SendFailedException("Mailbox busy", null, none, notSentYet, none)));
        willThrow(busy).given(mail).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> sender.send(new OutboundMessage(UUID.randomUUID(), "dina@example.com", "Hi", "Hello")))
                .isInstanceOf(DeliveryException.class)
                .isNotInstanceOf(UndeliverableException.class);
    }

    @Test
    void smtpFailureIsReportedAsADeliveryFailureWorthRetrying() {
        MailSendException smtpDown = new MailSendException("connection refused");
        willThrow(smtpDown).given(mail).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> sender.send(new OutboundMessage(UUID.randomUUID(), "dina@example.com", "Hi", "Hello")))
                .isInstanceOf(DeliveryException.class)
                .isNotInstanceOf(UndeliverableException.class)
                .hasCause(smtpDown);
    }

    private SimpleMailMessage sentEmail() {
        ArgumentCaptor<SimpleMailMessage> email = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(email.capture());
        return email.getValue();
    }
}
