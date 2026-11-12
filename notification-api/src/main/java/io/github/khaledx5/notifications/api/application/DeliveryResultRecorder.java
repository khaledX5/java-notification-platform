package io.github.khaledx5.notifications.api.application;

import io.github.khaledx5.notifications.api.domain.Notification;
import io.github.khaledx5.notifications.api.domain.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Applies the delivery results reported by the worker to the stored notifications.
 *
 * <p>Results arrive at least once, so recording one is idempotent: a notification that already
 * reached a final status keeps it, whatever arrives afterwards.
 */
@Service
public class DeliveryResultRecorder {

    private static final Logger log = LoggerFactory.getLogger(DeliveryResultRecorder.class);

    private final NotificationRepository notifications;
    private final NotificationMetrics metrics;
    private final Clock clock;

    public DeliveryResultRecorder(NotificationRepository notifications, NotificationMetrics metrics, Clock clock) {
        this.notifications = notifications;
        this.metrics = metrics;
        this.clock = clock;
    }

    public void delivered(UUID notificationId) {
        complete(notificationId, notification -> notification.markDelivered(now()));
    }

    public void failed(UUID notificationId, String reason) {
        complete(notificationId, notification -> notification.markFailed(reason, now()));
    }

    private void complete(UUID notificationId, Consumer<Notification> result) {
        Optional<Notification> found = notifications.findById(notificationId);
        if (found.isEmpty()) {
            log.warn("Ignoring a delivery result for unknown notification {}", notificationId);
            return;
        }
        Notification notification = found.get();
        if (notification.status().isTerminal()) {
            log.info(
                    "Ignoring a repeated delivery result: notification {} is already {}",
                    notificationId,
                    notification.status());
            return;
        }
        result.accept(notification);
        notifications.save(notification);
        metrics.completed(notification.status());
    }

    /** Millisecond precision, so a timestamp reads back from the database exactly as it was written. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
