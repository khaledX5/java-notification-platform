package io.github.khaledx5.notifications.api.web;

import io.github.khaledx5.notifications.api.application.NotificationService;
import io.github.khaledx5.notifications.api.domain.Notification;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Tag(name = OpenApiConfiguration.NOTIFICATIONS_TAG, description = "Request notifications and track them")
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Operation(
            summary = "Request a notification",
            description = "Send the content inline (body, optional subject) or name a stored template "
                    + "(templateKey, variables). The notification is accepted as QUEUED and delivered "
                    + "asynchronously; read it back to follow its status.")
    @ApiResponse(responseCode = "201", description = "Accepted; the Location header points to the notification")
    @ApiResponse(
            responseCode = "400",
            description = "The body is malformed or fails validation; rejected fields are listed under errors",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "422",
            description = "The template does not exist, or a template variable is missing",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "503",
            description = "The notification could not be queued for delivery; it is stored as FAILED",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    ResponseEntity<NotificationResponse> create(@Valid @RequestBody CreateNotificationRequest request) {
        Notification notification = request.usesTemplate()
                ? notifications.requestFromTemplate(
                        request.recipient(), request.channel(), request.templateKey(), request.variablesOrEmpty())
                : notifications.request(request.recipient(), request.channel(), request.subject(), request.body());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(notification.id())
                .toUri();
        return ResponseEntity.created(location).body(NotificationResponse.from(notification));
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
