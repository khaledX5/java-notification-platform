package io.github.khaledx5.notifications.worker.application;

import io.github.khaledx5.notifications.events.DeliveryChannel;

/**
 * The recipient already got as many messages as the limit allows right now. Like any other failure
 * that may pass, the delivery is tried again later.
 */
public class RateLimitedException extends DeliveryException {

    private static final long serialVersionUID = 1L;

    public RateLimitedException(DeliveryChannel channel) {
        super("The recipient is over the rate limit for " + channel);
    }
}
