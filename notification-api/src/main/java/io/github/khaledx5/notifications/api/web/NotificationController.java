package io.github.khaledx5.notifications.api.web;

import io.github.khaledx5.notifications.api.application.NotificationService;
import io.github.khaledx5.notifications.api.domain.Notification;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @PostMapping
    ResponseEntity<NotificationResponse> create(@Valid @RequestBody CreateNotificationRequest request) {
        Notification notification =
                notifications.request(request.recipient(), request.channel(), request.subject(), request.body());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(notification.id())
                .toUri();
        return ResponseEntity.created(location).body(NotificationResponse.from(notification));
    }

    @GetMapping("/{id}")
    NotificationResponse get(@PathVariable UUID id) {
        return NotificationResponse.from(notifications.getById(id));
    }
}
