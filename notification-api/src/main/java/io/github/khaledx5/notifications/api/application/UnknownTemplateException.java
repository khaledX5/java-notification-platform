package io.github.khaledx5.notifications.api.application;

/** Thrown when a notification request names a template that does not exist. */
public class UnknownTemplateException extends RuntimeException {

    public UnknownTemplateException(String templateKey) {
        super("Template '%s' does not exist".formatted(templateKey));
    }
}
