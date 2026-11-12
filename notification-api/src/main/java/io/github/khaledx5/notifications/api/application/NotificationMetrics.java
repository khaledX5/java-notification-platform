package io.github.khaledx5.notifications.api.application;

import io.github.khaledx5.notifications.api.domain.Channel;
import io.github.khaledx5.notifications.api.domain.NotificationStatus;

/**
 * Outgoing port for the numbers that tell whether the API is doing its job: how much comes in, how
 * much of it reaches the broker, and how it ends.
 */
public interface NotificationMetrics {

    /** A new notification was stored and queued. */
    void accepted(Channel channel);

    /** A repeated request was answered with the notification of the first one. */
    void replayed();

    /** The broker confirmed an event from the outbox. */
    void eventPublished();

    /** An event from the outbox could not be published; it will be tried again. */
    void eventPublishFailed();

    /** A notification reached a final status. */
    void completed(NotificationStatus finalStatus);
}
