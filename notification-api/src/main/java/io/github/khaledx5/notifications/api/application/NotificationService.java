package io.github.khaledx5.notifications.api.application;

import io.github.khaledx5.notifications.api.domain.Channel;
import io.github.khaledx5.notifications.api.domain.MissingTemplateVariableException;
import io.github.khaledx5.notifications.api.domain.Notification;
import io.github.khaledx5.notifications.api.domain.NotificationRepository;
import io.github.khaledx5.notifications.api.domain.RenderedTemplate;
import io.github.khaledx5.notifications.api.domain.Template;
import io.github.khaledx5.notifications.api.domain.TemplateRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Use cases for accepting notification requests and reading them back. */
@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final TemplateRepository templates;
    private final Outbox outbox;
    private final Clock clock;

    public NotificationService(
            NotificationRepository notifications, TemplateRepository templates, Outbox outbox, Clock clock) {
        this.notifications = notifications;
        this.templates = templates;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Accepts a new notification and queues it for delivery.
     *
     * <p>The notification and the event that announces it are stored in one transaction. The broker
     * is not involved here: the {@link OutboxRelay} publishes the event afterwards, so a notification
     * is accepted even while the broker is down, and none is ever stored without its event.
     *
     * <p>The notification is queued from the start. The worker can report a result within
     * milliseconds of the event going out, and that report must find a notification it is allowed to
     * complete.
     */
    @Transactional
    public Notification request(String recipient, Channel channel, String subject, String body) {
        Notification notification = Notification.request(recipient, channel, subject, body, now());
        notification.markQueued(now());
        notifications.save(notification);
        outbox.add(OutboxEvent.requested(notification));
        return notification;
    }

    /**
     * Accepts a new notification whose subject and body come from a stored template.
     *
     * @throws UnknownTemplateException if no template has this key
     * @throws MissingTemplateVariableException if a placeholder of the template has no value
     */
    @Transactional
    public Notification requestFromTemplate(
            String recipient, Channel channel, String templateKey, Map<String, String> variables) {
        Template template =
                templates.findByKey(templateKey).orElseThrow(() -> new UnknownTemplateException(templateKey));
        RenderedTemplate content = template.render(variables);
        return request(recipient, channel, content.subject(), content.body());
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
