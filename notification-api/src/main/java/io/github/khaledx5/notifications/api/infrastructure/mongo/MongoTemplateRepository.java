package io.github.khaledx5.notifications.api.infrastructure.mongo;

import io.github.khaledx5.notifications.api.domain.Template;
import io.github.khaledx5.notifications.api.domain.TemplateRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** MongoDB adapter for {@link TemplateRepository}: the source of truth for templates. */
@Repository("mongoTemplateRepository")
class MongoTemplateRepository implements TemplateRepository {

    private final SpringDataTemplateRepository documents;

    MongoTemplateRepository(SpringDataTemplateRepository documents) {
        this.documents = documents;
    }

    @Override
    public Template save(Template template) {
        documents.save(TemplateDocument.from(template));
        return template;
    }

    @Override
    public Optional<Template> findByKey(String key) {
        return documents.findById(key).map(TemplateDocument::toDomain);
    }
}
