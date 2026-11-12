package io.github.khaledx5.notifications.api.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.khaledx5.notifications.api.domain.Channel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxRelayTest {

    private static final Instant START = Instant.parse("2026-01-10T09:00:00Z");
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration CLAIM_DURATION = Duration.ofSeconds(30);
    private static final int BATCH_SIZE = 2;

    private final AdjustableClock clock = new AdjustableClock(START);
    private final InMemoryOutbox outbox = new InMemoryOutbox();
    private final RecordingPublisher broker = new RecordingPublisher();
    private final StubTracePropagation tracing = new StubTracePropagation();
    private final RecordingMetrics metrics = new RecordingMetrics();
    private final OutboxRelay relay = new OutboxRelay(
            outbox, broker, tracing, metrics, new OutboxProperties(POLL_INTERVAL, BATCH_SIZE, CLAIM_DURATION), clock);

    @Test
    void emptyOutboxLeavesTheBrokerAlone() {
        assertThat(relay.relayPending()).isZero();

        assertThat(broker.attempted).isEmpty();
    }

    @Test
    void waitingEventsArePublishedOldestFirstAndMarked() {
        OutboxEvent first = outbox.addWaiting();
        OutboxEvent second = outbox.addWaiting();

        assertThat(relay.relayPending()).isEqualTo(2);

        assertThat(broker.published).containsExactly(first, second);
        assertThat(outbox.publishedAt).containsOnlyKeys(first.id(), second.id());
    }

    @Test
    void eventIsMarkedWithTheTimeItWasPublished() {
        OutboxEvent event = outbox.addWaiting();
        clock.advance(Duration.ofSeconds(3));

        relay.relayPending();

        assertThat(outbox.publishedAt).containsEntry(event.id(), START.plusSeconds(3));
    }

    @Test
    void oneRunPublishesAtMostOneBatch() {
        outbox.addWaiting();
        outbox.addWaiting();
        OutboxEvent third = outbox.addWaiting();

        assertThat(relay.relayPending()).isEqualTo(BATCH_SIZE);
        assertThat(broker.published).doesNotContain(third);

        assertThat(relay.relayPending()).isEqualTo(1);
        assertThat(broker.published).contains(third);
    }

    @Test
    void publishedEventIsNotPublishedAgain() {
        outbox.addWaiting();
        relay.relayPending();

        assertThat(relay.relayPending()).isZero();

        assertThat(broker.published).hasSize(1);
    }

    @Test
    void runStopsAtTheFirstEventTheBrokerDoesNotTake() {
        OutboxEvent first = outbox.addWaiting();
        outbox.addWaiting();
        broker.down = true;

        assertThat(relay.relayPending()).isZero();

        assertThat(broker.attempted).containsExactly(first);
        assertThat(outbox.publishedAt).isEmpty();
    }

    @Test
    void eventTheBrokerDidNotTakeWaitsForItsClaimToRunOutWhileOthersGoAhead() {
        OutboxEvent failed = outbox.addWaiting();
        OutboxEvent behind = outbox.addWaiting();
        broker.down = true;
        relay.relayPending();
        broker.down = false;

        assertThat(relay.relayPending()).isEqualTo(1);
        assertThat(broker.published).containsExactly(behind);

        clock.advance(CLAIM_DURATION);

        assertThat(relay.relayPending()).isEqualTo(1);
        assertThat(broker.published).containsExactly(behind, failed);
    }

    @Test
    void eventThatWasPublishedButNotMarkedIsPublishedAgainWithTheSameId() {
        OutboxEvent event = outbox.addWaiting();
        outbox.loseNextMark = true;
        relay.relayPending();
        clock.advance(CLAIM_DURATION);

        relay.relayPending();

        assertThat(broker.published).extracting(OutboxEvent::id).containsExactly(event.id(), event.id());
    }

    @Test
    void eachEventIsPublishedInTheTraceOfTheRequestThatCausedIt() {
        Map<String, String> firstTrace =
                Map.of("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        Map<String, String> secondTrace =
                Map.of("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        outbox.add(requested(START, firstTrace));
        outbox.add(requested(START.plusMillis(1), secondTrace));

        relay.relayPending();

        assertThat(tracing.resumed).containsExactly(firstTrace, secondTrace);
    }

    @Test
    void publishedAndFailedEventsAreCounted() {
        outbox.addWaiting();
        outbox.addWaiting();
        relay.relayPending();
        outbox.addWaiting();
        broker.down = true;
        relay.relayPending();

        assertThat(metrics.eventsPublished).isEqualTo(2);
        assertThat(metrics.eventPublishFailures).isEqualTo(1);
    }

    @Test
    void outboxReportsHowManyEventsAreStillWaiting() {
        outbox.addWaiting();
        outbox.addWaiting();
        outbox.addWaiting();

        relay.relayPending();

        assertThat(outbox.countWaiting()).isEqualTo(1);
    }

    private static OutboxEvent requested(Instant occurredAt, Map<String, String> trace) {
        return new OutboxEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "+201000000000",
                Channel.SMS,
                null,
                "Code 4821",
                occurredAt,
                trace);
    }

    private static OutboxEvent requested(Instant occurredAt) {
        return new OutboxEvent(
                UUID.randomUUID(), UUID.randomUUID(), "+201000000000", Channel.SMS, null, "Code 4821", occurredAt);
    }

    private static final class RecordingPublisher implements NotificationEventPublisher {

        private final List<OutboxEvent> attempted = new ArrayList<>();
        private final List<OutboxEvent> published = new ArrayList<>();
        private boolean down;

        @Override
        public void publishRequested(OutboxEvent event) {
            attempted.add(event);
            if (down) {
                throw new EventPublishingException("broker is down", new IllegalStateException());
            }
            published.add(event);
        }
    }

    /** Keeps events in the order they were added and hands them out under the rules of the port. */
    private final class InMemoryOutbox implements Outbox {

        private final List<OutboxEvent> events = new ArrayList<>();
        private final Map<UUID, Instant> claimedUntil = new HashMap<>();
        private final Map<UUID, Instant> publishedAt = new HashMap<>();
        /** Stands for a relay that stopped right after the broker confirmed the event. */
        private boolean loseNextMark;

        OutboxEvent addWaiting() {
            OutboxEvent event = requested(clock.instant());
            add(event);
            return event;
        }

        @Override
        public void add(OutboxEvent event) {
            events.add(event);
        }

        @Override
        public Optional<OutboxEvent> claimNext(Instant now, Duration claimDuration) {
            Optional<OutboxEvent> next = events.stream()
                    .filter(event -> !publishedAt.containsKey(event.id()))
                    .filter(event -> !claimedUntil.containsKey(event.id())
                            || !claimedUntil.get(event.id()).isAfter(now))
                    .findFirst();
            next.ifPresent(event -> claimedUntil.put(event.id(), now.plus(claimDuration)));
            return next;
        }

        @Override
        public void markPublished(UUID eventId, Instant now) {
            if (loseNextMark) {
                loseNextMark = false;
                return;
            }
            publishedAt.put(eventId, now);
        }

        @Override
        public long countWaiting() {
            return events.stream()
                    .filter(event -> !publishedAt.containsKey(event.id()))
                    .count();
        }
    }

    private static final class AdjustableClock extends Clock {

        private Instant now;

        private AdjustableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
