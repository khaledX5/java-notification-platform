package io.github.khaledx5.notifications.api.application;

import io.github.khaledx5.notifications.api.domain.Channel;
import io.github.khaledx5.notifications.api.domain.Notification;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The announcement that a notification is waiting to be delivered, as it is kept in the outbox.
 *
 * <p>It is a snapshot taken when the notification was accepted, so the event that goes out later
 * says what was true at that moment.
 *
 * @param id identifies the event; it stays the same however often the event is published, which is
 *     how consumers recognise a repeat
 * @param subject optional; channels such as SMS have none
 * @param trace the trace of the request that caused the event, as captured by {@link
 *     TracePropagation}; empty if there was none
 */
public record OutboxEvent(
        UUID id,
        UUID notificationId,
        String recipient,
        Channel channel,
        String subject,
        String body,
        Instant occurredAt,
        Map<String, String> trace) {

    public OutboxEvent {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(notificationId, "notificationId must not be null");
        Objects.requireNonNull(recipient, "recipient must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        trace = trace == null ? Map.of() : Map.copyOf(trace);
    }

    /** An event that is not part of any trace. */
    public OutboxEvent(
            UUID id,
            UUID notificationId,
            String recipient,
            Channel channel,
            String subject,
            String body,
            Instant occurredAt) {
        this(id, notificationId, recipient, channel, subject, body, occurredAt, Map.of());
    }

    public static OutboxEvent requested(Notification notification, Map<String, String> trace) {
        return new OutboxEvent(
                UUID.randomUUID(),
                notification.id(),
                notification.recipient(),
                notification.channel(),
                notification.subject().orElse(null),
                notification.body(),
                notification.updatedAt(),
                trace);
    }
}
