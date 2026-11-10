package io.github.khaledx5.notifications.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.khaledx5.notifications.api.application.TemplateNotFoundException;
import io.github.khaledx5.notifications.api.application.TemplateService;
import io.github.khaledx5.notifications.api.domain.Template;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@WebMvcTest(TemplateController.class)
class TemplateControllerTests {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private TemplateService templates;

    @Test
    void storesATemplateUnderTheKeyInThePath() {
        Template template = new Template("order-shipped", "Order {{orderId}} shipped", "Hi {{name}}");
        given(templates.save(template)).willReturn(template);

        MvcTestResult result = put("order-shipped", """
                {"subject": "Order {{orderId}} shipped", "body": "Hi {{name}}"}
                """);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {
                  "key": "order-shipped",
                  "subject": "Order {{orderId}} shipped",
                  "body": "Hi {{name}}",
                  "placeholders": ["orderId", "name"]
                }
                """);
    }

    @Test
    void templateWithoutSubjectLeavesTheFieldOut() {
        Template template = new Template("login-code", null, "Code {{code}}");
        given(templates.save(template)).willReturn(template);

        MvcTestResult result = put("login-code", """
                {"body": "Code {{code}}"}
                """);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().doesNotHavePath("$.subject");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Order-Shipped", "order_shipped", "order--shipped", "-order"})
    void rejectsAMalformedKey(String key) {
        MvcTestResult result = put(key, """
                {"body": "Hi {{name}}"}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Invalid request content.");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("key");
        verifyNoInteractions(templates);
    }

    @Test
    void rejectsABlankBodyAndNamesTheField() {
        MvcTestResult result = put("login-code", """
                {"body": " "}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("body");
        verifyNoInteractions(templates);
    }

    @Test
    void returnsAStoredTemplate() {
        given(templates.getByKey("login-code")).willReturn(new Template("login-code", null, "Code {{code}}"));

        MvcTestResult result =
                mvc.get().uri("/api/v1/templates/{key}", "login-code").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {"key": "login-code", "body": "Code {{code}}", "placeholders": ["code"]}
                """);
    }

    @Test
    void unknownTemplateIsANotFoundProblem() {
        given(templates.getByKey("missing")).willThrow(new TemplateNotFoundException("missing"));

        MvcTestResult result =
                mvc.get().uri("/api/v1/templates/{key}", "missing").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().isLenientlyEqualTo("""
                {
                  "title": "Template not found",
                  "status": 404,
                  "detail": "No template with key 'missing'",
                  "instance": "/api/v1/templates/missing"
                }
                """);
    }

    private MvcTestResult put(String key, String json) {
        return mvc.put()
                .uri("/api/v1/templates/{key}", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }
}
