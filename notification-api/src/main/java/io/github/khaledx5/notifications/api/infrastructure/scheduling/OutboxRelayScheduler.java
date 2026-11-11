package io.github.khaledx5.notifications.api.infrastructure.scheduling;

import io.github.khaledx5.notifications.api.application.OutboxRelay;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the outbox relay over and over, with a pause after each run.
 *
 * <p>A fixed delay, not a fixed rate: a run that takes long because the broker is slow is never
 * overlapped by the next one. A run that fails is logged by the scheduler and the next one starts as
 * usual.
 */
@Component
class OutboxRelayScheduler {

    private final OutboxRelay relay;

    OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${notifications.outbox.poll-interval}")
    void relayPendingEvents() {
        relay.relayPending();
    }
}
