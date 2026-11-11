package io.github.khaledx5.notifications.worker.application;

import io.github.khaledx5.notifications.events.DeliveryChannel;

/**
 * Outgoing port that limits how many messages one recipient gets over one channel.
 *
 * <p>It protects people from a flood, for example when a caller loops over the same address by
 * mistake, and it keeps the worker inside the sending limits of the providers.
 */
public interface DeliveryRateLimiter {

    /**
     * Asks for permission to send one more message, and uses it up if it is granted.
     *
     * @return false if the recipient already got as many messages as the limit allows right now
     */
    boolean tryAcquire(DeliveryChannel channel, String recipient);
}
