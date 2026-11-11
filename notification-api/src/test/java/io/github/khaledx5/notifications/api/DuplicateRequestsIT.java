package io.github.khaledx5.notifications.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Idempotency keys end to end, against a real Redis and a real MongoDB: however often and however
 * fast a request is repeated, one key gives one notification and one event.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DuplicateRequestsIT {

    private static final String NOTIFICATIONS = "/api/v1/notifications";
    private static final int CONCURRENT_REQUESTS = 12;

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private MongoTemplate mongo;

    @Autowired
    private JsonMapper json;

    @Test
    void repeatedRequestReturnsTheFirstNotificationAndCreatesNothingNew() {
        String recipient = uniqueRecipient();
        String key = uniqueKey();

        MvcTestResult first = post(emailTo(recipient), key);
        MvcTestResult repeat = post(emailTo(recipient), key);

        assertThat(first).hasStatus(HttpStatus.CREATED).doesNotContainHeader("Idempotency-Replayed");
        assertThat(repeat).hasStatus(HttpStatus.CREATED).hasHeader("Idempotency-Replayed", "true");
        assertThat(repeat.getResponse().getHeader("Location"))
                .isEqualTo(first.getResponse().getHeader("Location"));
        assertThat(idOf(repeat)).isEqualTo(idOf(first));
        assertThat(notificationsFor(recipient)).isEqualTo(1);
        assertThat(outboxEntriesFor(idOf(first))).isEqualTo(1);
    }

    @Test
    void requestsThatArriveAtTheSameTimeWithOneKeyCreateOneNotification() throws Exception {
        String recipient = uniqueRecipient();
        String key = uniqueKey();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> request = () -> {
            start.await();
            return post(emailTo(recipient), key).getResponse().getStatus();
        };

        List<Integer> statuses;
        try (ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS)) {
            List<Future<Integer>> answers = IntStream.range(0, CONCURRENT_REQUESTS)
                    .mapToObj(i -> executor.submit(request))
                    .toList();
            start.countDown();
            statuses = answers.stream().map(DuplicateRequestsIT::get).toList();
        }

        // A repeat either gets the finished notification or is told that the first request still runs.
        assertThat(statuses)
                .contains(HttpStatus.CREATED.value())
                .containsOnly(HttpStatus.CREATED.value(), HttpStatus.CONFLICT.value());
        assertThat(notificationsFor(recipient)).isEqualTo(1);
    }

    @Test
    void keyThatIsSentAgainWithADifferentBodyIsRejectedAndCreatesNothing() {
        String recipient = uniqueRecipient();
        String key = uniqueKey();
        post(emailTo(recipient), key);

        MvcTestResult different = post("""
                {"recipient": "%s", "channel": "EMAIL", "subject": "Order delayed", "body": "Sorry for the wait"}
                """.formatted(recipient), key);

        assertThat(different).hasStatus(422).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(different).bodyJson().extractingPath("$.title").isEqualTo("Idempotency key reused");
        assertThat(notificationsFor(recipient)).isEqualTo(1);
    }

    @Test
    void keyOfARequestThatWasRejectedCanBeUsedAgain() {
        String recipient = uniqueRecipient();
        String key = uniqueKey();
        MvcTestResult rejected = post("""
                {"recipient": "%s", "channel": "EMAIL", "templateKey": "no-such-template"}
                """.formatted(recipient), key);
        assertThat(rejected).hasStatus(422);
        assertThat(rejected).bodyJson().extractingPath("$.title").isEqualTo("Unknown template");

        MvcTestResult corrected = post(emailTo(recipient), key);

        assertThat(corrected).hasStatus(HttpStatus.CREATED).doesNotContainHeader("Idempotency-Replayed");
        assertThat(notificationsFor(recipient)).isEqualTo(1);
    }

    @Test
    void sameBodyUnderDifferentKeysGivesTwoNotifications() {
        String recipient = uniqueRecipient();

        post(emailTo(recipient), uniqueKey());
        post(emailTo(recipient), uniqueKey());

        assertThat(notificationsFor(recipient)).isEqualTo(2);
    }

    @Test
    void requestsWithoutAKeyAreNeverTreatedAsRepeats() {
        String recipient = uniqueRecipient();

        post(emailTo(recipient), null);
        post(emailTo(recipient), null);

        assertThat(notificationsFor(recipient)).isEqualTo(2);
    }

    private MvcTestResult post(String body, String idempotencyKey) {
        MockMvcTester.MockMvcRequestBuilder request = mvc.post()
                .uri(NOTIFICATIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return request.exchange();
    }

    private long notificationsFor(String recipient) {
        return mongo.getCollection("notifications").countDocuments(new Document("recipient", recipient));
    }

    private long outboxEntriesFor(String notificationId) {
        return mongo.getCollection("outbox").countDocuments(new Document("notificationId", notificationId));
    }

    private String idOf(MvcTestResult result) {
        try {
            return json.readTree(result.getResponse().getContentAsString())
                    .get("id")
                    .asString();
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static int get(Future<Integer> answer) {
        try {
            return answer.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (java.util.concurrent.ExecutionException ex) {
            throw new IllegalStateException(ex.getCause());
        }
    }

    private static String emailTo(String recipient) {
        return """
                {"recipient": "%s", "channel": "EMAIL", "subject": "Order shipped", "body": "Your order is on its way"}
                """.formatted(recipient);
    }

    private static String uniqueRecipient() {
        return "dina-" + UUID.randomUUID() + "@example.com";
    }

    private static String uniqueKey() {
        return "order-" + UUID.randomUUID();
    }
}
