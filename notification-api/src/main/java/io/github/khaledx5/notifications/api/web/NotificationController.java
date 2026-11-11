package io.github.khaledx5.notifications.api.web;

import io.github.khaledx5.notifications.api.application.IdempotentRequests;
import io.github.khaledx5.notifications.api.application.NotificationService;
import io.github.khaledx5.notifications.api.domain.Notification;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Tag(name = OpenApiConfiguration.NOTIFICATIONS_TAG, description = "Request notifications and track them")
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotency-Replayed";

    /** Visible ASCII without spaces: long enough for a UUID or a client's own order reference. */
    static final String IDEMPOTENCY_KEY_PATTERN = "[\\x21-\\x7E]{1,255}";

    private final NotificationService notifications;
    private final IdempotentRequests idempotentRequests;

    NotificationController(NotificationService notifications, IdempotentRequests idempotentRequests) {
        this.notifications = notifications;
        this.idempotentRequests = idempotentRequests;
    }

    @Operation(
            summary = "Request a notification",
            description = "Send the content inline (body, optional subject) or name a stored template "
                    + "(templateKey, variables). The notification is accepted as QUEUED and delivered "
                    + "asynchronously; read it back to follow its status. Send an Idempotency-Key header to "
                    + "make the request safe to repeat: a repeat returns the notification of the first "
                    + "request, marked with the header Idempotency-Replayed: true.")
    @ApiResponse(responseCode = "201", description = "Accepted; the Location header points to the notification")
    @ApiResponse(
            responseCode = "400",
            description = "The body is malformed or fails validation; rejected fields are listed under errors",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The first request with this Idempotency-Key is still being processed",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "422",
            description = "The template does not exist, a template variable is missing, or the "
                    + "Idempotency-Key was already used with a different body",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "503",
            description = "The Idempotency-Key could not be checked; nothing was stored",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    ResponseEntity<NotificationResponse> create(
            @Parameter(description = "Chosen by the client, unique per notification it wants to send")
                    @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false)
                    @Pattern(regexp = IDEMPOTENCY_KEY_PATTERN, message = "must be 1 to 255 visible ASCII characters")
                    String idempotencyKey,
            @Valid @RequestBody CreateNotificationRequest request) {
        if (idempotencyKey == null) {
            Notification notification = accept(request);
            return created(notification).body(NotificationResponse.from(notification));
        }
        IdempotentRequests.Result result =
                idempotentRequests.execute(idempotencyKey, request.fingerprint(), () -> accept(request));
        ResponseEntity.BodyBuilder response = created(result.notification());
        if (result.replayed()) {
            response.header(REPLAYED_HEADER, "true");
        }
        return response.body(NotificationResponse.from(result.notification()));
    }

    private Notification accept(CreateNotificationRequest request) {
        return request.usesTemplate()
                ? notifications.requestFromTemplate(
                        request.recipient(), request.channel(), request.templateKey(), request.variablesOrEmpty())
                : notifications.request(request.recipient(), request.channel(), request.subject(), request.body());
    }

    private static ResponseEntity.BodyBuilder created(Notification notification) {
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(notification.id())
                .toUri();
        return ResponseEntity.created(location);
    }

    @Operation(summary = "Get a notification and its delivery status")
    @ApiResponse(responseCode = "200", description = "The notification")
    @ApiResponse(
            responseCode = "400",
            description = "The id is not a UUID",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No notification has this id",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{id}")
    NotificationResponse get(@PathVariable UUID id) {
        return NotificationResponse.from(notifications.getById(id));
    }
}
