package io.github.khaledx5.notifications.worker.application;

import io.github.khaledx5.notifications.events.DeliveryChannel;
import io.github.khaledx5.notifications.events.NotificationRequested;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Delivers a requested notification through the sender of its channel and reports the result. */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private final Map<DeliveryChannel, ChannelSender> senders;
    private final DeliveryStatusPublisher statuses;

    /**
     * @throws IllegalStateException if a channel has no sender or more than one, so a wiring mistake
     *     stops the worker at startup instead of losing notifications later
     */
    public DeliveryService(List<ChannelSender> senders, DeliveryStatusPublisher statuses) {
        this.senders = byChannel(senders);
        this.statuses = statuses;
    }

    /**
     * Sends the notification and reports it as delivered, or as failed with the reason the sender
     * gave.
     *
     * @throws StatusPublishingException if the result could not be reported; the event must then be
     *     processed again
     */
    public void deliver(NotificationRequested event) {
        OutboundMessage message =
                new OutboundMessage(event.notificationId(), event.recipient(), event.subject(), event.body());
        try {
            senders.get(event.channel()).send(message);
        } catch (DeliveryException ex) {
            log.warn("Notification {} was not delivered over {}", event.notificationId(), event.channel(), ex);
            statuses.failed(event.notificationId(), ex.getMessage());
            return;
        }
        log.info("Delivered notification {} over {}", event.notificationId(), event.channel());
        statuses.delivered(event.notificationId());
    }

    private static Map<DeliveryChannel, ChannelSender> byChannel(List<ChannelSender> senders) {
        Map<DeliveryChannel, ChannelSender> byChannel = new EnumMap<>(DeliveryChannel.class);
        for (ChannelSender sender : senders) {
            ChannelSender previous = byChannel.put(sender.channel(), sender);
            if (previous != null) {
                throw new IllegalStateException("More than one sender for channel " + sender.channel());
            }
        }
        Set<DeliveryChannel> missing = EnumSet.allOf(DeliveryChannel.class);
        missing.removeAll(byChannel.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("No sender for channels " + missing);
        }
        return byChannel;
    }
}
