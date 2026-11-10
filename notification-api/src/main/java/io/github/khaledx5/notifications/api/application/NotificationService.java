package io.github.khaledx5.notifications.api.application;

import io.github.khaledx5.notifications.api.domain.Channel;
import io.github.khaledx5.notifications.api.domain.Notification;
import io.github.khaledx5.notifications.api.domain.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Use cases for accepting notification requests and reading them back. */
@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final Clock clock;

    public NotificationService(NotificationRepository notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    /** Accepts a new notification and stores it as pending. */
    public Notification request(String recipient, Channel channel, String subject, String body) {
        Notification notification = Notification.request(recipient, channel, subject, body, now());
        return notifications.save(notification);
    }

    /** @throws NotificationNotFoundException if no notification has this id */
    public Notification getById(UUID id) {
        return notifications.findById(id).orElseThrow(() -> new NotificationNotFoundException(id));
    }

    /** Millisecond precision, so a timestamp reads back from the database exactly as it was written. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
