package io.github.khaledx5.notifications.api.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class NotificationTest {

    private static final Instant CREATED = Instant.parse("2026-01-10T09:00:00Z");
    private static final Instant LATER = CREATED.plusSeconds(5);

    @Test
    void newNotificationStartsPending() {
        Notification notification = Notification.request(
                "dina@example.com", Channel.EMAIL, "Order shipped", "Your order is on its way", CREATED);

        assertThat(notification.id()).isNotNull();
        assertThat(notification.recipient()).isEqualTo("dina@example.com");
        assertThat(notification.channel()).isEqualTo(Channel.EMAIL);
        assertThat(notification.subject()).contains("Order shipped");
        assertThat(notification.body()).isEqualTo("Your order is on its way");
        assertThat(notification.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.failureReason()).isEmpty();
        assertThat(notification.createdAt()).isEqualTo(CREATED);
        assertThat(notification.updatedAt()).isEqualTo(CREATED);
    }

    @Test
    void eachNotificationGetsItsOwnId() {
        assertThat(pendingSms().id()).isNotEqualTo(pendingSms().id());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void subjectIsOptional(String subject) {
        Notification notification = Notification.request("+201000000000", Channel.SMS, subject, "Code 4821", CREATED);

        assertThat(notification.subject()).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void rejectsBlankRecipient(String recipient) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Notification.request(recipient, Channel.SMS, null, "Code 4821", CREATED))
                .withMessage("recipient must not be blank");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void rejectsBlankBody(String body) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Notification.request("+201000000000", Channel.SMS, null, body, CREATED))
                .withMessage("body must not be blank");
    }

    @Test
    void rejectsMissingChannel() {
        assertThatThrownBy(() -> Notification.request("+201000000000", null, null, "Code 4821", CREATED))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("channel must not be null");
    }

    @Test
    void queuedThenDeliveredIsTheHappyPath() {
        Notification notification = pendingSms();

        notification.markQueued(LATER);
        assertThat(notification.status()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(notification.updatedAt()).isEqualTo(LATER);

        Instant deliveredAt = LATER.plusSeconds(2);
        notification.markDelivered(deliveredAt);
        assertThat(notification.status()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(notification.updatedAt()).isEqualTo(deliveredAt);
        assertThat(notification.createdAt()).isEqualTo(CREATED);
        assertThat(notification.failureReason()).isEmpty();
    }

    @Test
    void failureKeepsTheReason() {
        Notification notification = pendingSms();
        notification.markQueued(LATER);

        notification.markFailed("provider rejected the number", LATER);

        assertThat(notification.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.failureReason()).contains("provider rejected the number");
    }

    @Test
    void pendingNotificationCanFailBeforeItIsQueued() {
        Notification notification = pendingSms();

        notification.markFailed("broker unavailable", LATER);

        assertThat(notification.status()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void cannotBeDeliveredBeforeItIsQueued() {
        Notification notification = pendingSms();

        assertThatThrownBy(() -> notification.markDelivered(LATER))
                .isInstanceOfSatisfying(IllegalStatusTransitionException.class, ex -> {
                    assertThat(ex.from()).isEqualTo(NotificationStatus.PENDING);
                    assertThat(ex.to()).isEqualTo(NotificationStatus.DELIVERED);
                })
                .hasMessageContaining(notification.id().toString());
        assertThat(notification.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.updatedAt()).isEqualTo(CREATED);
    }

    @Test
    void deliveredNotificationCannotChangeAgain() {
        Notification notification = pendingSms();
        notification.markQueued(LATER);
        notification.markDelivered(LATER);

        assertThatThrownBy(() -> notification.markFailed("late failure", LATER))
                .isInstanceOf(IllegalStatusTransitionException.class);
        assertThat(notification.status()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(notification.failureReason()).isEmpty();
    }

    @Test
    void failureNeedsAReasonAndLeavesStatusUntouchedWithoutOne() {
        Notification notification = pendingSms();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> notification.markFailed(" ", LATER))
                .withMessage("reason must not be blank");
        assertThat(notification.status()).isEqualTo(NotificationStatus.PENDING);
    }

    private static Notification pendingSms() {
        return Notification.request("+201000000000", Channel.SMS, null, "Code 4821", CREATED);
    }
}
