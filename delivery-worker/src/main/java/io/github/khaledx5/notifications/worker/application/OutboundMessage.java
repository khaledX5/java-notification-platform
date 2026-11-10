package io.github.khaledx5.notifications.worker.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What a {@link ChannelSender} needs to deliver one notification.
 *
 * @param subject may be null; channels such as SMS have none
 */
public record OutboundMessage(UUID notificationId, String recipient, String subject, String body) {

    public OutboundMessage {
        Objects.requireNonNull(notificationId, "notificationId must not be null");
        Objects.requireNonNull(recipient, "recipient must not be null");
        Objects.requireNonNull(body, "body must not be null");
    }

    public Optional<String> optionalSubject() {
        return Optional.ofNullable(subject);
    }
}
