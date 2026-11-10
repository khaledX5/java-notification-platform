package io.github.khaledx5.notifications.api.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code PUT /api/v1/templates/{key}}. */
record UpsertTemplateRequest(
        @Size(max = CreateNotificationRequest.MAX_SUBJECT_LENGTH)
        String subject,

        @NotBlank @Size(max = CreateNotificationRequest.MAX_BODY_LENGTH)
        String body) {}
