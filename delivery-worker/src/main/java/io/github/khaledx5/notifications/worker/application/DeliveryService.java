package io.github.khaledx5.notifications.worker.application;

import io.github.khaledx5.notifications.events.DeliveryChannel;
import io.github.khaledx5.notifications.events.NotificationRequested;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Delivers a requested notification through the sender of its channel and reports the result.
 *
 * <p>The result of an event is remembered right after the message is sent and before it is reported.
 * An event that arrives again is therefore never sent twice, and its report is simply repeated: if
 * the first report was lost the notification still gets its status, and if it was not, the API
 * ignores the repeat.
 */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    static final String ABANDONED_REASON = "Delivery kept failing and was abandoned";

    private final Map<DeliveryChannel, ChannelSender> senders;
    private final DeliveryStatusPublisher statuses;
    private final ProcessedEvents processedEvents;

    /**
     * @throws IllegalStateException if a channel has no sender or more than one, so a wiring mistake
     *     stops the worker at startup instead of losing notifications later
     */
    public DeliveryService(
            List<ChannelSender> senders, DeliveryStatusPublisher statuses, ProcessedEvents processedEvents) {
        this.senders = byChannel(senders);
        this.statuses = statuses;
        this.processedEvents = processedEvents;
    }

    /**
     * Sends the notification and reports it as delivered. A notification that can never be delivered
     * is reported as failed right away, with the reason the sender gave. An event that was already
     * handled is not sent again; only its report is repeated.
     *
     * @throws DeliveryException if the delivery failed in a way that may pass; the caller is expected
     *     to try again later, and to call {@link #abandon} when it stops trying
     * @throws StatusPublishingException if the result could not be reported; the event must then be
     *     processed again
     */
    public void deliver(NotificationRequested event) {
        Optional<DeliveryResult> earlier = processedEvents.resultOf(event.eventId());
        if (earlier.isPresent()) {
            log.info(
                    "Event {} of notification {} was already handled; repeating its report",
                    event.eventId(),
                    event.notificationId());
            report(event, earlier.get());
            return;
        }
        DeliveryResult result = send(event);
        processedEvents.remember(event.eventId(), result);
        report(event, result);
    }

    /**
     * Reports a notification as failed after its delivery was retried without success. If the event
     * was in fact handled before, for example because only its report kept failing, that earlier
     * result is reported instead.
     *
     * @throws StatusPublishingException if the result could not be reported
     */
    public void abandon(NotificationRequested event) {
        Optional<DeliveryResult> earlier = processedEvents.resultOf(event.eventId());
        if (earlier.isPresent()) {
            report(event, earlier.get());
            return;
        }
        log.error("Giving up on notification {} over {}", event.notificationId(), event.channel());
        DeliveryResult result = DeliveryResult.failed(ABANDONED_REASON);
        processedEvents.remember(event.eventId(), result);
        report(event, result);
    }

    private DeliveryResult send(NotificationRequested event) {
        OutboundMessage message =
                new OutboundMessage(event.notificationId(), event.recipient(), event.subject(), event.body());
        try {
            senders.get(event.channel()).send(message);
        } catch (UndeliverableException ex) {
            log.warn("Notification {} cannot be delivered over {}", event.notificationId(), event.channel(), ex);
            return DeliveryResult.failed(ex.getMessage());
        }
        log.info("Delivered notification {} over {}", event.notificationId(), event.channel());
        return DeliveryResult.delivered();
    }

    private void report(NotificationRequested event, DeliveryResult result) {
        if (result.isDelivered()) {
            statuses.delivered(event.notificationId());
        } else {
            statuses.failed(event.notificationId(), result.failureReason());
        }
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
