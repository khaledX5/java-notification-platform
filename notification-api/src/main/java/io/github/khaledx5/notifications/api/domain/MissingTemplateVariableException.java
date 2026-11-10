package io.github.khaledx5.notifications.api.domain;

import java.util.List;
import java.util.Set;

/** Thrown when a template is rendered without a value for one or more of its placeholders. */
public class MissingTemplateVariableException extends RuntimeException {

    private final String templateKey;
    private final List<String> missingVariables;

    public MissingTemplateVariableException(String templateKey, Set<String> missingVariables) {
        super("Template '%s' is missing values for: %s".formatted(templateKey, String.join(", ", missingVariables)));
        this.templateKey = templateKey;
        this.missingVariables = List.copyOf(missingVariables);
    }

    public String templateKey() {
        return templateKey;
    }

    public List<String> missingVariables() {
        return missingVariables;
    }
}
