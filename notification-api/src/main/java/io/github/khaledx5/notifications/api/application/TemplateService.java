package io.github.khaledx5.notifications.api.application;

import io.github.khaledx5.notifications.api.domain.Template;
import io.github.khaledx5.notifications.api.domain.TemplateRepository;
import org.springframework.stereotype.Service;

/** Use cases for managing notification templates. */
@Service
public class TemplateService {

    private final TemplateRepository templates;

    public TemplateService(TemplateRepository templates) {
        this.templates = templates;
    }

    /** Creates the template, or replaces the one that already has this key. */
    public Template save(Template template) {
        return templates.save(template);
    }

    /** @throws TemplateNotFoundException if no template has this key */
    public Template getByKey(String key) {
        return templates.findByKey(key).orElseThrow(() -> new TemplateNotFoundException(key));
    }
}
