package io.github.khaledx5.notifications.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP API end to end: controllers, validation, problem details, the service, MongoDB and the
 * Redis template cache, with nothing mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NotificationApiFlowTests {

    private static final String NOTIFICATIONS = "/api/v1/notifications";
    private static final String TEMPLATES = "/api/v1/templates/{key}";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private MongoTemplate mongo;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JsonMapper json;

    @Test
    void inlineNotificationCanBeReadBackExactlyAsItWasAccepted() {
        MvcTestResult created = post(NOTIFICATIONS, """
                {
                  "recipient": "dina@example.com",
                  "channel": "EMAIL",
                  "subject": "Order shipped",
                  "body": "Your order is on its way"
                }
                """);

        assertThat(created).hasStatus(HttpStatus.CREATED);
        String location = created.getResponse().getHeader("Location");
        String acceptedJson = body(created);

        MvcTestResult fetched = mvc.get().uri(location).exchange();

        assertThat(fetched).hasStatusOk();
        assertThat(fetched).bodyJson().isStrictlyEqualTo(acceptedJson);
        assertThat(fetched).bodyJson().extractingPath("$.status").isEqualTo("PENDING");
    }

    @Test
    void acceptedNotificationIsStoredInMongo() {
        MvcTestResult created = post(NOTIFICATIONS, """
                {"recipient": "+201000000000", "channel": "SMS", "body": "Code 4821"}
                """);
        String id = read(created).get("id").asString();

        Document stored = mongo.getCollection("notifications")
                .find(new Document("_id", id))
                .first();

        assertThat(stored).isNotNull();
        assertThat(stored.getString("recipient")).isEqualTo("+201000000000");
        assertThat(stored.getString("channel")).isEqualTo("SMS");
        assertThat(stored.getString("status")).isEqualTo("PENDING");
        assertThat(stored.containsKey("subject")).isFalse();
    }

    @Test
    void notificationFromATemplateIsRenderedAndTheTemplateIsCached() {
        String key = uniqueTemplateKey();
        MvcTestResult template = put(key, """
                {"subject": "Order {{orderId}} shipped", "body": "Hi {{name}}, order {{orderId}} is on its way."}
                """);
        assertThat(template).hasStatusOk();
        assertThat(template)
                .bodyJson()
                .extractingPath("$.placeholders")
                .asArray()
                .containsExactly("orderId", "name");

        MvcTestResult created = post(NOTIFICATIONS, """
                {
                  "recipient": "dina@example.com",
                  "channel": "EMAIL",
                  "templateKey": "%s",
                  "variables": {"orderId": "A-1042", "name": "Dina"}
                }
                """.formatted(key));

        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(created).bodyJson().extractingPath("$.subject").isEqualTo("Order A-1042 shipped");
        assertThat(created).bodyJson().extractingPath("$.body").isEqualTo("Hi Dina, order A-1042 is on its way.");
        assertThat(redis.hasKey("notification-api:template:" + key)).isTrue();
    }

    @Test
    void editingATemplateDoesNotChangeNotificationsThatWereAlreadyAccepted() {
        String key = uniqueTemplateKey();
        put(key, """
                {"body": "Your code is {{code}}"}
                """);
        MvcTestResult created = post(NOTIFICATIONS, """
                {"recipient": "+201000000000", "channel": "SMS", "templateKey": "%s", "variables": {"code": "4821"}}
                """.formatted(key));
        String location = created.getResponse().getHeader("Location");

        put(key, """
                {"body": "Use {{code}} to sign in"}
                """);

        assertThat(mvc.get().uri(location)).bodyJson().extractingPath("$.body").isEqualTo("Your code is 4821");
        MvcTestResult next = post(NOTIFICATIONS, """
                {"recipient": "+201000000000", "channel": "SMS", "templateKey": "%s", "variables": {"code": "7310"}}
                """.formatted(key));
        assertThat(next).bodyJson().extractingPath("$.body").isEqualTo("Use 7310 to sign in");
    }

    @Test
    void rejectedRequestsStoreNothing() {
        long before = mongo.getCollection("notifications").countDocuments();
        String key = uniqueTemplateKey();
        put(key, """
                {"body": "Hi {{name}}"}
                """);

        MvcTestResult invalid = post(NOTIFICATIONS, """
                {"recipient": "", "channel": "SMS", "body": "Code 4821"}
                """);
        MvcTestResult unknownTemplate = post(NOTIFICATIONS, """
                {"recipient": "+201000000000", "channel": "SMS", "templateKey": "no-such-template"}
                """);
        MvcTestResult missingVariable = post(NOTIFICATIONS, """
                {"recipient": "+201000000000", "channel": "SMS", "templateKey": "%s"}
                """.formatted(key));

        assertThat(invalid).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(unknownTemplate).hasStatus(422).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(missingVariable).hasStatus(422).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(missingVariable)
                .bodyJson()
                .extractingPath("$.missingVariables")
                .asArray()
                .containsExactly("name");
        assertThat(mongo.getCollection("notifications").countDocuments()).isEqualTo(before);
    }

    @Test
    void unknownNotificationAndTemplateAreNotFoundProblems() {
        MvcTestResult notification =
                mvc.get().uri(NOTIFICATIONS + "/{id}", UUID.randomUUID()).exchange();
        MvcTestResult template = mvc.get().uri(TEMPLATES, uniqueTemplateKey()).exchange();

        assertThat(notification).hasStatus(HttpStatus.NOT_FOUND).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(template).hasStatus(HttpStatus.NOT_FOUND).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    private MvcTestResult post(String uri, String body) {
        return mvc.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResult put(String key, String body) {
        return mvc.put()
                .uri(TEMPLATES, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private JsonNode read(MvcTestResult result) {
        return json.readTree(body(result));
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String uniqueTemplateKey() {
        return "flow-" + UUID.randomUUID();
    }
}
