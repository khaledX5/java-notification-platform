package io.github.khaledx5.notifications.api.web;

import io.github.khaledx5.notifications.api.domain.Channel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/notifications}. */
record CreateNotificationRequest(
        @NotBlank @Size(max = MAX_RECIPIENT_LENGTH) String recipient,
        @NotNull Channel channel,
        @Size(max = MAX_SUBJECT_LENGTH) String subject,
        @NotBlank @Size(max = MAX_BODY_LENGTH) String body) {

    static final int MAX_RECIPIENT_LENGTH = 320;
    static final int MAX_SUBJECT_LENGTH = 200;
    static final int MAX_BODY_LENGTH = 4000;
}
