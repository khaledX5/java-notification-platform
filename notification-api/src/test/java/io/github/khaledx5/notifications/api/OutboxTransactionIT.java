package io.github.khaledx5.notifications.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.khaledx5.notifications.api.application.Outbox;
import io.github.khaledx5.notifications.api.application.OutboxEvent;
import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * The promise of the outbox against a real MongoDB: a notification and the event that announces it
 * are stored together or not at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, OutboxTransactionIT.TestDoubles.class})
class OutboxTransactionIT {

    private static final String NOTIFICATIONS = "/api/v1/notifications";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String SMS_JSON = """
            {"recipient": "+201000000000", "channel": "SMS", "body": "Code 4821"}
            """;

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private MongoTemplate mongo;

    @Autowired
    private JsonMapper json;

    @Autowired
    private UnwritableOutbox outbox;

    @Test
    void acceptedNotificationHasAnOutboxEntryThatTheRelayMarksAsPublished() throws UnsupportedEncodingException {
        MvcTestResult created = postSms();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        String id = json.readTree(created.getResponse().getContentAsString())
                .get("id")
                .asString();

        Document entry = mongo.getCollection("outbox")
                .find(new Document("notificationId", id))
                .first();
        assertThat(entry).isNotNull();
        assertThat(entry.getString("recipient")).isEqualTo("+201000000000");
        assertThat(entry.getString("body")).isEqualTo("Code 4821");

        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(mongo.getCollection("outbox")
                                .find(new Document("notificationId", id))
                                .first())
                        .containsKey("publishedAt")
                        .doesNotContainKey("claimedUntil"));
    }

    @Test
    void notificationIsNotStoredWhenItsEventCannotBeAddedToTheOutbox() {
        long notificationsBefore = mongo.getCollection("notifications").countDocuments();
        long entriesBefore = mongo.getCollection("outbox").countDocuments();
        outbox.failNextWrite(new IllegalStateException("the outbox cannot be written"));

        MvcTestResult rejected = postSms();

        assertThat(rejected)
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(mongo.getCollection("notifications").countDocuments()).isEqualTo(notificationsBefore);
        assertThat(mongo.getCollection("outbox").countDocuments()).isEqualTo(entriesBefore);
    }

    private MvcTestResult postSms() {
        return mvc.post()
                .uri(NOTIFICATIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(SMS_JSON)
                .exchange();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestDoubles {

        @Bean
        @Primary
        UnwritableOutbox unwritableOutbox(@Qualifier("mongoOutbox") Outbox mongoOutbox) {
            return new UnwritableOutbox(mongoOutbox);
        }
    }

    /**
     * The real outbox, except that its next write can be made to fail. A decorator instead of a
     * Mockito spy: the relay calls the outbox from its scheduler thread all the time, and stubbing a
     * spy while another thread calls it can put the stub on the wrong method.
     */
    static class UnwritableOutbox implements Outbox {

        private final Outbox outbox;
        private final AtomicReference<RuntimeException> nextWriteFailure = new AtomicReference<>();

        UnwritableOutbox(Outbox outbox) {
            this.outbox = outbox;
        }

        void failNextWrite(RuntimeException failure) {
            nextWriteFailure.set(failure);
        }

        @Override
        public void add(OutboxEvent event) {
            RuntimeException failure = nextWriteFailure.getAndSet(null);
            if (failure != null) {
                throw failure;
            }
            outbox.add(event);
        }

        @Override
        public Optional<OutboxEvent> claimNext(Instant now, Duration claimDuration) {
            return outbox.claimNext(now, claimDuration);
        }

        @Override
        public void markPublished(UUID eventId, Instant now) {
            outbox.markPublished(eventId, now);
        }

        @Override
        public long countWaiting() {
            return outbox.countWaiting();
        }
    }
}
