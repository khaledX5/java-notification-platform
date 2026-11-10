package io.github.khaledx5.notifications.api.application;

import io.github.khaledx5.notifications.api.domain.Notification;

/** Outgoing port for telling the rest of the platform about notifications. */
public interface NotificationEventPublisher {

    /**
     * Announces that the notification is waiting to be delivered. Returns only once the broker has
     * confirmed the event.
     *
     * @throws EventPublishingException if the event could not be handed to the broker
     */
    void publishRequested(Notification notification);
}
