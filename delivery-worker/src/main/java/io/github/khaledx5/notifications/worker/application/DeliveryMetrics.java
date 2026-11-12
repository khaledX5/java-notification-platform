package io.github.khaledx5.notifications.worker.application;

import io.github.khaledx5.notifications.events.DeliveryChannel;

/**
 * Outgoing port for counting what happens to the events the worker handles. One number per channel
 * and kind of attempt is enough to see a provider going down, a recipient being flooded, or the
 * broker handing out duplicates.
 */
public interface DeliveryMetrics {

    void record(DeliveryChannel channel, Attempt attempt);

    /** How one attempt to handle a request event ended. */
    enum Attempt {
        /** The provider accepted the message. */
        DELIVERED,
        /** The message can never be delivered as it is; the notification failed. */
        UNDELIVERABLE,
        /** The provider did not accept the message this time; the event is retried. */
        PROVIDER_FAILURE,
        /** The recipient was over the rate limit; nothing was sent and the event is retried. */
        RATE_LIMITED,
        /** The event had been handled before; nothing was sent. */
        DUPLICATE,
        /** The event ran out of retries; the notification failed. */
        ABANDONED
    }
}
