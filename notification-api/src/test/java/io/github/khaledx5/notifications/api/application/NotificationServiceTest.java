package io.github.khaledx5.notifications.api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.khaledx5.notifications.api.domain.Channel;
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

class NotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-10T09:00:00.123456789Z");

    private final InMemoryNotificationRepository repository = new InMemoryNotificationRepository();
    private final NotificationService service = new NotificationService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void requestedNotificationIsStoredAsPending() {
        Notification notification =
                service.request("dina@example.com", Channel.EMAIL, "Order shipped", "Your order is on its way");

        assertThat(notification.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(repository.findById(notification.id())).containsSame(notification);
    }

    @Test
    void timestampsAreCutToMilliseconds() {
        Notification notification = service.request("+201000000000", Channel.SMS, null, "Code 4821");

        assertThat(notification.createdAt()).isEqualTo(Instant.parse("2026-01-10T09:00:00.123Z"));
        assertThat(notification.updatedAt()).isEqualTo(notification.createdAt());
    }

    @Test
    void invalidRequestIsRejectedAndNothingIsStored() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.request(" ", Channel.SMS, null, "Code 4821"));

        assertThat(repository.size()).isZero();
    }

    @Test
    void findsAStoredNotificationById() {
        Notification notification = service.request("+201000000000", Channel.SMS, null, "Code 4821");

        assertThat(service.getById(notification.id())).isSameAs(notification);
    }

    @Test
    void unknownIdIsReportedAsNotFound() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> service.getById(unknown))
                .isInstanceOf(NotificationNotFoundException.class)
                .hasMessageContaining(unknown.toString());
    }

    private static final class InMemoryNotificationRepository implements NotificationRepository {

        private final Map<UUID, Notification> stored = new HashMap<>();

        @Override
        public Notification save(Notification notification) {
            stored.put(notification.id(), notification);
            return notification;
        }

        @Override
        public Optional<Notification> findById(UUID id) {
            return Optional.ofNullable(stored.get(id));
        }

        int size() {
            return stored.size();
        }
    }
}
