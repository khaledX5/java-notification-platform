package io.github.khaledx5.notifications.events;

/** Names of the Kafka topics the services exchange events on. */
public final class Topics {

    /** {@link NotificationRequested} events, written by notification-api and read by delivery-worker. */
    public static final String NOTIFICATIONS_REQUESTED = "notifications.requested";

    /** {@link DeliveryStatusChanged} events, written by delivery-worker and read by notification-api. */
    public static final String NOTIFICATIONS_STATUS = "notifications.status";

    private Topics() {}
}
