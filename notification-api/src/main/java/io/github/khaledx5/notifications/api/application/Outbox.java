package io.github.khaledx5.notifications.api.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Outgoing port for the transactional outbox.
 *
 * <p>An event is not sent to the broker by the code that causes it. It is stored here, in the same
 * database transaction as the change it announces, and published later by the {@link OutboxRelay}.
 * The change and its event are therefore saved together or not at all, whatever the broker does.
 */
public interface Outbox {

    /** Stores the event. Call it inside the transaction that saves the change the event announces. */
    void add(OutboxEvent event);

    /**
     * Hands out the oldest event that still has to be published and that no relay is working on, and
     * reserves it for the caller.
     *
     * <p>Claiming is atomic, so several relays never get the same event at the same time. A claim
     * that is not followed by {@link #markPublished} simply runs out, and the event is handed out
     * again: that covers a relay that died and a publish that failed.
     *
     * @param claimDuration how long the event is reserved for the caller
     */
    Optional<OutboxEvent> claimNext(Instant now, Duration claimDuration);

    /** Records that the broker confirmed the event, so it is not handed out again. */
    void markPublished(UUID eventId, Instant now);
}
