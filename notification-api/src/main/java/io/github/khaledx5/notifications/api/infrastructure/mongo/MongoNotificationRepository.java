package io.github.khaledx5.notifications.api.infrastructure.mongo;

import io.github.khaledx5.notifications.api.domain.Notification;
import io.github.khaledx5.notifications.api.domain.NotificationRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** MongoDB adapter for {@link NotificationRepository}. */
@Repository
class MongoNotificationRepository implements NotificationRepository {

    private final SpringDataNotificationRepository documents;

    MongoNotificationRepository(SpringDataNotificationRepository documents) {
        this.documents = documents;
    }

    @Override
    public Notification save(Notification notification) {
        documents.save(NotificationDocument.from(notification));
        return notification;
    }

    @Override
    public Optional<Notification> findById(UUID id) {
        return documents.findById(id.toString()).map(NotificationDocument::toDomain);
    }
}
