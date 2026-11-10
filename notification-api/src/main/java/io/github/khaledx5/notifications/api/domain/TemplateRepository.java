package io.github.khaledx5.notifications.api.domain;

import java.util.Optional;

/** Storage port for templates. */
public interface TemplateRepository {

    /** Inserts the template, or replaces the stored one with the same key. */
    Template save(Template template);

    Optional<Template> findByKey(String key);
}
