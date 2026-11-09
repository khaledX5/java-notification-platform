package io.github.khaledx5.notifications.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.khaledx5.notifications.api.domain.Channel;
import io.github.khaledx5.notifications.api.domain.Notification;
import io.github.khaledx5.notifications.api.domain.NotificationStatus;
import java.time.Instant;
import java.util.UUID;

/** JSON view of a notification. Fields without a value are left out. */
@JsonInclude(JsonInclude.Include.NON_NULL)
record NotificationResponse(
        UUID id,
        String recipient,
        Channel channel,
        String subject,
        String body,
        NotificationStatus status,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.id(),
                notification.recipient(),
                notification.channel(),
                notification.subject().orElse(null),
                notification.body(),
                notification.status(),
                notification.failureReason().orElse(null),
                notification.createdAt(),
                notification.updatedAt());
    }
}
