package io.github.khaledx5.notifications.api.web;

import io.github.khaledx5.notifications.api.application.TemplateService;
import io.github.khaledx5.notifications.api.domain.Template;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = OpenApiConfiguration.TEMPLATES_TAG, description = "Reusable notification content")
@RestController
@RequestMapping("/api/v1/templates")
class TemplateController {

    private final TemplateService templates;

    TemplateController(TemplateService templates) {
        this.templates = templates;
    }

    /** Creates the template or replaces it; the key in the path makes the call idempotent. */
    @Operation(
            summary = "Create or replace a template",
            description = "Placeholders are written as {{name}}. The response lists the placeholders a "
                    + "notification request has to supply.")
    @ApiResponse(responseCode = "200", description = "The stored template")
    @ApiResponse(
            responseCode = "400",
            description = "The key or the body fails validation; rejected fields are listed under errors",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping("/{key}")
    TemplateResponse upsert(
            @PathVariable @Size(max = Template.MAX_KEY_LENGTH) @Pattern(regexp = Template.KEY_PATTERN) String key,
            @Valid @RequestBody UpsertTemplateRequest request) {
        Template template = templates.save(new Template(key, request.subject(), request.body()));
        return TemplateResponse.from(template);
    }

    @Operation(summary = "Get a template")
    @ApiResponse(responseCode = "200", description = "The template")
    @ApiResponse(
            responseCode = "404",
            description = "No template has this key",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{key}")
    TemplateResponse get(@PathVariable String key) {
        return TemplateResponse.from(templates.getByKey(key));
    }
}
