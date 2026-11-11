package io.github.khaledx5.notifications.api.infrastructure.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.khaledx5.notifications.api.TestcontainersConfiguration;
import io.github.khaledx5.notifications.api.application.OutboxEvent;
import io.github.khaledx5.notifications.api.domain.Channel;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Query;

/** The outbox against a real MongoDB: what the relay is handed, in which order, and when. */
@DataMongoTest
@Import({TestcontainersConfiguration.class, MongoOutbox.class})
class MongoOutboxIT {

    private static final Instant NOW = Instant.parse("2026-01-10T09:00:00Z");
    private static final Duration CLAIM = Duration.ofSeconds(30);
    private static final int WAITING_EVENTS = 40;
    private static final int RELAYS = 8;

    @Autowired
    private MongoOutbox outbox;

    @Autowired
    private MongoTemplate mongo;

    @BeforeEach
    void emptyTheOutbox() {
        mongo.remove(new Query(), OutboxDocument.class);
    }

    @Test
    void emptyOutboxHandsOutNothing() {
        assertThat(outbox.claimNext(NOW, CLAIM)).isEmpty();
    }

    @Test
    void addedEventIsHandedOutExactlyAsItWasStored() {
        OutboxEvent email = new OutboxEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "dina@example.com",
                Channel.EMAIL,
                "Order shipped",
                "Your order is on its way",
                NOW);
        outbox.add(email);

        assertThat(outbox.claimNext(NOW.plusSeconds(1), CLAIM)).contains(email);
    }

    @Test
    void eventWithoutSubjectComesBackWithoutOne() {
        OutboxEvent sms = occurredAt(NOW);
        outbox.add(sms);

        assertThat(outbox.claimNext(NOW, CLAIM))
                .hasValueSatisfying(claimed -> assertThat(claimed.subject()).isNull());
    }

    @Test
    void eventsAreHandedOutOldestFirst() {
        OutboxEvent newer = occurredAt(NOW.plusSeconds(2));
        OutboxEvent oldest = occurredAt(NOW);
        OutboxEvent middle = occurredAt(NOW.plusSeconds(1));
        outbox.add(newer);
        outbox.add(oldest);
        outbox.add(middle);
        Instant later = NOW.plusSeconds(10);

        assertThat(outbox.claimNext(later, CLAIM)).contains(oldest);
        assertThat(outbox.claimNext(later, CLAIM)).contains(middle);
        assertThat(outbox.claimNext(later, CLAIM)).contains(newer);
        assertThat(outbox.claimNext(later, CLAIM)).isEmpty();
    }

    @Test
    void claimedEventIsNotHandedOutAgainWhileTheClaimLasts() {
        outbox.add(occurredAt(NOW));
        outbox.claimNext(NOW, CLAIM);

        assertThat(outbox.claimNext(NOW.plus(CLAIM).minusMillis(1), CLAIM)).isEmpty();
    }

    @Test
    void eventWhoseClaimRanOutIsHandedOutAgainAndTheAttemptIsCounted() {
        OutboxEvent event = occurredAt(NOW);
        outbox.add(event);
        outbox.claimNext(NOW, CLAIM);

        assertThat(outbox.claimNext(NOW.plus(CLAIM), CLAIM)).contains(event);

        assertThat(stored(event).attempts()).isEqualTo(2);
    }

    @Test
    void publishedEventIsNeverHandedOutAgain() {
        OutboxEvent event = occurredAt(NOW);
        outbox.add(event);
        outbox.claimNext(NOW, CLAIM);

        outbox.markPublished(event.id(), NOW.plusSeconds(1));

        assertThat(outbox.claimNext(NOW.plus(CLAIM).plusSeconds(60), CLAIM)).isEmpty();
        assertThat(stored(event).publishedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(stored(event).claimedUntil()).isNull();
    }

    @Test
    void relaysWorkingAtTheSameTimeNeverGetTheSameEvent() throws Exception {
        IntStream.range(0, WAITING_EVENTS).forEach(i -> outbox.add(occurredAt(NOW.plusMillis(i))));
        CountDownLatch start = new CountDownLatch(1);
        Callable<List<UUID>> relay = () -> {
            start.await();
            List<UUID> claimed = new ArrayList<>();
            Optional<OutboxEvent> next = outbox.claimNext(NOW.plusSeconds(60), CLAIM);
            while (next.isPresent()) {
                claimed.add(next.get().id());
                next = outbox.claimNext(NOW.plusSeconds(60), CLAIM);
            }
            return claimed;
        };

        List<UUID> claimedByAll = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(RELAYS)) {
            List<Future<List<UUID>>> relays = IntStream.range(0, RELAYS)
                    .mapToObj(i -> executor.submit(relay))
                    .toList();
            start.countDown();
            for (Future<List<UUID>> claimed : relays) {
                claimedByAll.addAll(claimed.get());
            }
        }

        assertThat(claimedByAll).hasSize(WAITING_EVENTS).doesNotHaveDuplicates();
    }

    @Test
    void indexesForTheRelayQueryAndForCleaningUpAreCreated() {
        assertThat(mongo.indexOps(OutboxDocument.class).getIndexInfo())
                .extracting(IndexInfo::getName)
                .contains("waiting_oldest_first", "published_expiry");
    }

    private OutboxDocument stored(OutboxEvent event) {
        return mongo.findById(event.id().toString(), OutboxDocument.class);
    }

    private static OutboxEvent occurredAt(Instant occurredAt) {
        return new OutboxEvent(
                UUID.randomUUID(), UUID.randomUUID(), "+201000000000", Channel.SMS, null, "Code 4821", occurredAt);
    }
}
