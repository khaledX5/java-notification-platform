package io.github.khaledx5.notifications.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A notification was accepted and is waiting to be delivered.
 *
 * <p>The event carries everything the worker needs, so it never has to call back into the API.
 *
 * @param eventId unique per event; consumers use it to recognise a redelivered event
 * @param notificationId the notification to deliver; also the record key, so events of one
 *     notification stay in order
 * @param subject optional; channels such as SMS have none
 */
public record NotificationRequested(
        UUID eventId,
        UUID notificationId,
        String recipient,
        DeliveryChannel channel,
        String subject,
        String body,
        Instant occurredAt) {

    public NotificationRequested {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(notificationId, "notificationId must not be null");
        Objects.requireNonNull(recipient, "recipient must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
