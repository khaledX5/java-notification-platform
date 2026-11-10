package io.github.khaledx5.notifications.api.application;

/** Thrown when no template exists for the requested key. */
public class TemplateNotFoundException extends RuntimeException {

    public TemplateNotFoundException(String key) {
        super("No template with key '%s'".formatted(key));
    }
}
