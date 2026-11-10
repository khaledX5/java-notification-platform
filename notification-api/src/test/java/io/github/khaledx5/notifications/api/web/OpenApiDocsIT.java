package io.github.khaledx5.notifications.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.khaledx5.notifications.api.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The generated OpenAPI description is part of the public contract, so its key parts are pinned. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiDocsIT {

    @Autowired
    private MockMvcTester mvc;

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
    void actuatorEndpointsAreNotPartOfTheApiDescription() {
        MvcTestResult docs = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(docs).bodyJson().doesNotHavePath("$.paths['/actuator/health']");
    }

    @Test
    void servesSwaggerUi() {
        assertThat(mvc.get().uri("/swagger-ui/index.html")).hasStatusOk();
    }
}
