package io.github.khaledx5.notifications.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.khaledx5.notifications.api.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The generated OpenAPI description is part of the public contract, so its key parts are pinned. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiDocsIT {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JsonMapper json;

    @Test
    void describesTheApi() {
        MvcTestResult docs = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(docs).hasStatusOk();
        assertThat(docs).bodyJson().extractingPath("$.info.title").isEqualTo("Notification API");
        assertThat(docs).bodyJson().extractingPath("$.info.version").isEqualTo("v1");
    }

    @Test
    void listsEveryEndpoint() {
        MvcTestResult docs = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(docs).bodyJson().hasPath("$.paths['/api/v1/notifications'].post");
        assertThat(docs).bodyJson().hasPath("$.paths['/api/v1/notifications/{id}'].get");
        assertThat(docs).bodyJson().hasPath("$.paths['/api/v1/templates/{key}'].put");
        assertThat(docs).bodyJson().hasPath("$.paths['/api/v1/templates/{key}'].get");
    }

    @Test
    void documentsErrorResponsesAsProblemDocuments() {
        MvcTestResult docs = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(docs)
                .bodyJson()
                .hasPath("$.paths['/api/v1/notifications'].post.responses['400'].content['application/problem+json']")
                .hasPath("$.paths['/api/v1/notifications'].post.responses['422'].content['application/problem+json']")
                .hasPath(
                        "$.paths['/api/v1/notifications/{id}'].get.responses['404'].content['application/problem+json']")
                .hasPath("$.paths['/api/v1/templates/{key}'].get.responses['404'].content['application/problem+json']");
        assertThat(docs).bodyJson().hasPath("$.paths['/api/v1/notifications'].post.responses['201']");
    }

    @Test
    void everyDocumentedRequestExampleIsAccepted() {
        JsonNode docs = apiDocs();
        JsonNode templateExamples = examplesOf(docs, "/api/v1/templates/{key}", "put");
        JsonNode notificationExamples = examplesOf(docs, "/api/v1/notifications", "post");

        assertThat(templateExamples.propertyNames()).containsExactly(RequestExamples.ORDER_SHIPPED_TEMPLATE);
        assertThat(notificationExamples.propertyNames())
                .containsExactlyInAnyOrder(
                        RequestExamples.INLINE_EMAIL, RequestExamples.TEMPLATE_EMAIL, RequestExamples.SMS);

        // The template example comes first, as the notification that uses it needs it.
        assertThat(mvc.put()
                        .uri("/api/v1/templates/{key}", RequestExamples.ORDER_SHIPPED_TEMPLATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyOf(templateExamples.get(RequestExamples.ORDER_SHIPPED_TEMPLATE))))
                .hasStatusOk();
        notificationExamples
                .properties()
                .forEach(example -> assertThat(mvc.post()
                                .uri("/api/v1/notifications")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(bodyOf(example.getValue())))
                        .as("example %s", example.getKey())
                        .hasStatus(HttpStatus.CREATED));
    }

    @Test
    void actuatorEndpointsAreNotPartOfTheApiDescription() {
        MvcTestResult docs = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(docs).bodyJson().doesNotHavePath("$.paths['/actuator/health']");
    }

    @Test
    void servesSwaggerUi() {
        assertThat(mvc.get().uri("/swagger-ui/index.html")).hasStatusOk();
    }

    private JsonNode apiDocs() {
        MvcTestResult docs = mvc.get().uri("/v3/api-docs").exchange();
        assertThat(docs).hasStatusOk();
        return json.readTree(docs.getResponse().getContentAsByteArray());
    }

    private static JsonNode examplesOf(JsonNode docs, String path, String method) {
        return docs.path("paths")
                .path(path)
                .path(method)
                .path("requestBody")
                .path("content")
                .path(MediaType.APPLICATION_JSON_VALUE)
                .path("examples");
    }

    /** The example as the client would send it; it is listed either as a JSON value or as text. */
    private String bodyOf(JsonNode example) {
        JsonNode value = example.path("value");
        return value.isString() ? value.stringValue() : json.writeValueAsString(value);
    }
}
