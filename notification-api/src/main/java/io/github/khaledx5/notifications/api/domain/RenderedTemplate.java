package io.github.khaledx5.notifications.api.domain;

/**
 * A template with its placeholders filled in.
 *
 * @param subject null when the template has no subject
 */
public record RenderedTemplate(String subject, String body) {}
