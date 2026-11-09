package io.github.khaledx5.notifications.api.domain;

import java.util.Optional;
import java.util.UUID;

/** Storage port for notifications. The domain depends on this, not on a database. */
public interface NotificationRepository {

    /** Inserts the notification, or replaces the stored one with the same id. */
    Notification save(Notification notification);

    Optional<Notification> findById(UUID id);
}
