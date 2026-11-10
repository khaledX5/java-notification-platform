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

/** Delivers a requested notification through the sender of its channel. */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private final Map<DeliveryChannel, ChannelSender> senders;

    /**
     * @throws IllegalStateException if a channel has no sender or more than one, so a wiring mistake
     *     stops the worker at startup instead of losing notifications later
     */
    public DeliveryService(List<ChannelSender> senders) {
        this.senders = byChannel(senders);
    }

    /** @throws DeliveryException if the channel's provider did not accept the message */
    public void deliver(NotificationRequested event) {
        OutboundMessage message =
                new OutboundMessage(event.notificationId(), event.recipient(), event.subject(), event.body());
        senders.get(event.channel()).send(message);
        log.info("Delivered notification {} over {}", event.notificationId(), event.channel());
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
