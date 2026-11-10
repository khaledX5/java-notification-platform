package io.github.khaledx5.notifications.api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.khaledx5.notifications.api.domain.Channel;
import io.github.khaledx5.notifications.api.domain.IllegalStatusTransitionException;
import io.github.khaledx5.notifications.api.domain.Notification;
import io.github.khaledx5.notifications.api.domain.NotificationRepository;
import io.github.khaledx5.notifications.api.domain.NotificationStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliveryResultRecorderTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-01-10T09:00:00.123Z");
    private static final Instant NOW = Instant.parse("2026-01-10T09:00:05.250999Z");

    private final InMemoryNotificationRepository repository = new InMemoryNotificationRepository();
    private final DeliveryResultRecorder recorder =
            new DeliveryResultRecorder(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void queuedNotificationBecomesDelivered() {
        Notification notification = queued();

        recorder.delivered(notification.id());

        Notification stored = repository.findById(notification.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(stored.failureReason()).isEmpty();
        assertThat(stored.updatedAt()).isEqualTo(Instant.parse("2026-01-10T09:00:05.250Z"));
        assertThat(repository.saves).isEqualTo(1);
    }

    @Test
    void queuedNotificationBecomesFailedWithTheReportedReason() {
        Notification notification = queued();

        recorder.failed(notification.id(), "The SMTP server did not accept the email");

        Notification stored = repository.findById(notification.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(stored.failureReason()).contains("The SMTP server did not accept the email");
    }

    @Test
    void repeatedResultChangesNothing() {
        Notification notification = queued();
        recorder.delivered(notification.id());

        recorder.delivered(notification.id());

        assertThat(repository.saves).isEqualTo(1);
    }

    @Test
    void laterResultDoesNotOverwriteAFinalStatus() {
        Notification notification = queued();
        recorder.delivered(notification.id());

        recorder.failed(notification.id(), "gateway is down");

        Notification stored = repository.findById(notification.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(stored.failureReason()).isEmpty();
    }

    @Test
    void resultForAnUnknownNotificationIsIgnored() {
        assertThatCode(() -> recorder.delivered(UUID.randomUUID())).doesNotThrowAnyException();

        assertThat(repository.saves).isZero();
    }

    @Test
    void resultForANotificationThatWasNeverQueuedIsRejected() {
        Notification pending = Notification.request("+201000000000", Channel.SMS, null, "Code 4821", REQUESTED_AT);
        repository.stored.put(pending.id(), pending);

        assertThatThrownBy(() -> recorder.delivered(pending.id())).isInstanceOf(IllegalStatusTransitionException.class);

        assertThat(repository.saves).isZero();
    }

    private Notification queued() {
        Notification notification =
                Notification.request("dina@example.com", Channel.EMAIL, "Order shipped", "On its way", REQUESTED_AT);
        notification.markQueued(REQUESTED_AT);
        repository.stored.put(notification.id(), notification);
        return notification;
    }

    private static final class InMemoryNotificationRepository implements NotificationRepository {

        private final Map<UUID, Notification> stored = new HashMap<>();
        private int saves;

        @Override
        public Notification save(Notification notification) {
            saves++;
            stored.put(notification.id(), notification);
            return notification;
        }

        @Override
        public Optional<Notification> findById(UUID id) {
            return Optional.ofNullable(stored.get(id));
        }
    }
}
