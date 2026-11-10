package io.github.khaledx5.notifications.worker.application;

import java.util.UUID;

/**
 * Outgoing port for reporting how a delivery ended. Both methods return only once the report is
 * safely handed over.
 */
public interface DeliveryStatusPublisher {

    /** @throws StatusPublishingException if the report could not be handed over */
    void delivered(UUID notificationId);

    /**
     * @param reason why the delivery failed, in words that are safe to show to an API client
     * @throws StatusPublishingException if the report could not be handed over
     */
    void failed(UUID notificationId, String reason);
}
