package io.github.khaledx5.notifications.api.web;

import io.github.khaledx5.notifications.api.application.TemplateService;
import io.github.khaledx5.notifications.api.domain.Template;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/templates")
class TemplateController {

    private final TemplateService templates;

    TemplateController(TemplateService templates) {
        this.templates = templates;
    }

    /** Creates the template or replaces it; the key in the path makes the call idempotent. */
    @PutMapping("/{key}")
    TemplateResponse upsert(
            @PathVariable @Size(max = Template.MAX_KEY_LENGTH) @Pattern(regexp = Template.KEY_PATTERN) String key,
            @Valid @RequestBody UpsertTemplateRequest request) {
        Template template = templates.save(new Template(key, request.subject(), request.body()));
        return TemplateResponse.from(template);
    }

    @GetMapping("/{key}")
    TemplateResponse get(@PathVariable String key) {
        return TemplateResponse.from(templates.getByKey(key));
    }
}
