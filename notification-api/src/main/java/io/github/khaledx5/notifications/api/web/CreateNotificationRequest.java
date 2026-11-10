package io.github.khaledx5.notifications.api.web;

import io.github.khaledx5.notifications.api.domain.Channel;
import io.github.khaledx5.notifications.api.domain.Template;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Body of {@code POST /api/v1/notifications}.
 *
 * <p>The content comes from exactly one place: either {@code body} (with an optional {@code
 * subject}) written by the caller, or a stored template named by {@code templateKey} and filled in
 * with {@code variables}.
 */
@ExactlyOneContentSource
record CreateNotificationRequest(
        @NotBlank @Size(max = MAX_RECIPIENT_LENGTH) String recipient,
        @NotNull Channel channel,
        @Size(max = MAX_SUBJECT_LENGTH) String subject,
        @Size(max = MAX_BODY_LENGTH) String body,

        @Size(max = Template.MAX_KEY_LENGTH) @Pattern(regexp = Template.KEY_PATTERN)
        String templateKey,

        Map<String, String> variables) {

    static final int MAX_RECIPIENT_LENGTH = 320;
    static final int MAX_SUBJECT_LENGTH = 200;
    static final int MAX_BODY_LENGTH = 4000;

    boolean usesTemplate() {
        return templateKey != null;
    }

    /** Never null, so callers do not have to care whether the field was sent. */
    Map<String, String> variablesOrEmpty() {
        return variables == null ? Map.of() : variables;
    }
}
