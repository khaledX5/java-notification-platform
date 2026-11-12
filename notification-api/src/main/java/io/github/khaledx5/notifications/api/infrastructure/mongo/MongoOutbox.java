package io.github.khaledx5.notifications.api.infrastructure.mongo;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import io.github.khaledx5.notifications.api.application.Outbox;
import io.github.khaledx5.notifications.api.application.OutboxEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * MongoDB adapter for {@link Outbox}.
 *
 * <p>An entry is claimed with a single {@code findAndModify}: MongoDB finds the oldest waiting entry
 * and stamps the claim on it in one atomic step, so two relays cannot both get it.
 */
@Repository
class MongoOutbox implements Outbox {

    private final MongoOperations mongo;

    MongoOutbox(MongoOperations mongo) {
        this.mongo = mongo;
    }

    @Override
    public void add(OutboxEvent event) {
        mongo.insert(OutboxDocument.waiting(event));
    }

    @Override
    public Optional<OutboxEvent> claimNext(Instant now, Duration claimDuration) {
        Criteria notClaimed = new Criteria()
                .orOperator(
                        where(OutboxDocument.CLAIMED_UNTIL).isNull(),
                        where(OutboxDocument.CLAIMED_UNTIL).lte(now));
        Query oldestWaiting = query(where(OutboxDocument.PUBLISHED_AT).isNull().andOperator(notClaimed))
                .with(Sort.by(OutboxDocument.OCCURRED_AT));
        Update claim = new Update()
                .set(OutboxDocument.CLAIMED_UNTIL, now.plus(claimDuration))
                .inc(OutboxDocument.ATTEMPTS, 1);

        OutboxDocument claimed = mongo.findAndModify(
                oldestWaiting, claim, FindAndModifyOptions.options().returnNew(true), OutboxDocument.class);
        return Optional.ofNullable(claimed).map(OutboxDocument::toEvent);
    }

    @Override
    public void markPublished(UUID eventId, Instant now) {
        Update published = new Update().set(OutboxDocument.PUBLISHED_AT, now).unset(OutboxDocument.CLAIMED_UNTIL);
        mongo.updateFirst(query(where("_id").is(eventId.toString())), published, OutboxDocument.class);
    }

    @Override
    public long countWaiting() {
        return mongo.count(query(where(OutboxDocument.PUBLISHED_AT).isNull()), OutboxDocument.class);
    }
}
