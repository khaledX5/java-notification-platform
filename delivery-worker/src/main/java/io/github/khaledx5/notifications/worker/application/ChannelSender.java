package io.github.khaledx5.notifications.worker.application;

import io.github.khaledx5.notifications.events.DeliveryChannel;

/**
 * Delivers messages over one channel. Supporting a new channel means adding one implementation; the
 * rest of the worker does not change.
 */
public interface ChannelSender {

    /** The channel this sender handles. Exactly one sender exists per channel. */
    DeliveryChannel channel();

    /**
     * Hands the message to the channel's provider.
     *
     * @throws UndeliverableException if the message can never be delivered, for example because the
     *     recipient is not valid for this channel
     * @throws DeliveryException if the provider did not accept the message this time
     */
    void send(OutboundMessage message);
}
