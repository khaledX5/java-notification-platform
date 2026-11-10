package io.github.khaledx5.notifications.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.khaledx5.notifications.api.domain.Template;
import java.util.Set;

/** JSON view of a template, with the placeholder names a caller has to supply. */
@JsonInclude(JsonInclude.Include.NON_NULL)
record TemplateResponse(String key, String subject, String body, Set<String> placeholders) {

    static TemplateResponse from(Template template) {
        return new TemplateResponse(template.key(), template.subject(), template.body(), template.placeholders());
    }
}
