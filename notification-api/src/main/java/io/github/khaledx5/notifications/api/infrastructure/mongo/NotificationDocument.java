package io.github.khaledx5.notifications.api.infrastructure.mongo;

import io.github.khaledx5.notifications.api.domain.Channel;
import io.github.khaledx5.notifications.api.domain.Notification;
import io.github.khaledx5.notifications.api.domain.NotificationStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** MongoDB shape of a {@link Notification}. The id is stored as its canonical string form. */
@Document(collection = "notifications")
record NotificationDocument(
        @Id String id,
        String recipient,
        Channel channel,
        String subject,
        String body,
        NotificationStatus status,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    static NotificationDocument from(Notification notification) {
        return new NotificationDocument(
                notification.id().toString(),
                notification.recipient(),
                notification.channel(),
                notification.subject().orElse(null),
                notification.body(),
                notification.status(),
                notification.failureReason().orElse(null),
                notification.createdAt(),
                notification.updatedAt());
    }

    Notification toDomain() {
        return Notification.restore(
                UUID.fromString(id), recipient, channel, subject, body, status, failureReason, createdAt, updatedAt);
    }
}
