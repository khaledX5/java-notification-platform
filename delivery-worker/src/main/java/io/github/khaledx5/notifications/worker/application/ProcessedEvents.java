package io.github.khaledx5.notifications.worker.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Outgoing port that remembers how each request event ended.
 *
 * <p>Kafka delivers an event at least once, so the same event can arrive again after a rebalance, a
 * retry or a restart. Its remembered result lets the worker repeat the report without sending the
 * message a second time.
 *
 * <p>This is a safety net on top of at-least-once delivery, not a guarantee: an implementation that
 * cannot reach its storage reports nothing as processed and forgets what it is asked to remember.
 */
public interface ProcessedEvents {

    /** Empty if the event was not handled before, or if that is not known. */
    Optional<DeliveryResult> resultOf(UUID eventId);

    void remember(UUID eventId, DeliveryResult result);
}
