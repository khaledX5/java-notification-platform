package io.github.khaledx5.notifications.api.infrastructure.mongo;

import io.github.khaledx5.notifications.api.domain.Template;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** MongoDB shape of a {@link Template}; the template key is the document id. */
@Document(collection = "templates")
record TemplateDocument(@Id String key, String subject, String body) {

    static TemplateDocument from(Template template) {
        return new TemplateDocument(template.key(), template.subject(), template.body());
    }

    Template toDomain() {
        return new Template(key, subject, body);
    }
}
