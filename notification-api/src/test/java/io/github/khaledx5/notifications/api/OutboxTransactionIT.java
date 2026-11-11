package io.github.khaledx5.notifications.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

import io.github.khaledx5.notifications.api.application.Outbox;
import java.io.UnsupportedEncodingException;
import java.time.Duration;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * The promise of the outbox against a real MongoDB: a notification and the event that announces it
 * are stored together or not at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
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

    @MockitoSpyBean
    private Outbox outbox;

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
        willThrow(new IllegalStateException("the outbox cannot be written"))
                .given(outbox)
                .add(any());

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
}
