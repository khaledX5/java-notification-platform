package io.github.khaledx5.notifications.api.infrastructure.mongo;

import io.github.khaledx5.notifications.api.application.OutboxEvent;
import io.github.khaledx5.notifications.api.domain.Channel;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * MongoDB shape of an {@link OutboxEvent}, plus the bookkeeping of the relay.
 *
 * <p>The compound index serves the only query of the relay: entries without {@code publishedAt},
 * oldest first. Published entries are kept for a week for troubleshooting and then removed by the
 * TTL index.
 *
 * @param trace trace headers of the request that caused the event; absent in entries without a trace
 * @param claimedUntil while this lies in the future a relay is publishing the entry
 * @param attempts how often the entry was handed to a relay; more than one means a publish failed or
 *     a relay stopped half-way
 * @param publishedAt when the broker confirmed the event; absent while it is waiting
 */
@Document(collection = OutboxDocument.COLLECTION)
@CompoundIndex(name = "waiting_oldest_first", def = "{'publishedAt': 1, 'occurredAt': 1}")
record OutboxDocument(
        @Id String id,
        String notificationId,
        String recipient,
        Channel channel,
        String subject,
        String body,
        Instant occurredAt,
        Map<String, String> trace,
        Instant claimedUntil,
        int attempts,

        @Indexed(name = "published_expiry", expireAfter = OutboxDocument.PUBLISHED_RETENTION)
        Instant publishedAt) {

    static final String COLLECTION = "outbox";
    static final String PUBLISHED_RETENTION = "7d";

    static final String OCCURRED_AT = "occurredAt";
    static final String CLAIMED_UNTIL = "claimedUntil";
    static final String ATTEMPTS = "attempts";
    static final String PUBLISHED_AT = "publishedAt";

    static OutboxDocument waiting(OutboxEvent event) {
        return new OutboxDocument(
                event.id().toString(),
                event.notificationId().toString(),
                event.recipient(),
                event.channel(),
                event.subject(),
                event.body(),
                event.occurredAt(),
                event.trace().isEmpty() ? null : event.trace(),
                null,
                0,
                null);
    }

    OutboxEvent toEvent() {
        return new OutboxEvent(
                UUID.fromString(id),
                UUID.fromString(notificationId),
                recipient,
                channel,
                subject,
                body,
                occurredAt,
                trace);
    }
}
