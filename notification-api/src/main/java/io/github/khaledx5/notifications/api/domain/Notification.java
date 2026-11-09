package io.github.khaledx5.notifications.api.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A request to notify one recipient over one channel, together with its delivery status.
 *
 * <p>The aggregate owns its lifecycle: status only changes through the {@code mark*} methods, which
 * reject transitions that {@link NotificationStatus} does not allow.
 */
public final class Notification {

    private final UUID id;
    private final String recipient;
    private final Channel channel;
    private final String subject;
    private final String body;
    private final Instant createdAt;

    private NotificationStatus status;
    private String failureReason;
    private Instant updatedAt;

    private Notification(UUID id, String recipient, Channel channel, String subject, String body, Instant createdAt) {
        this.id = id;
        this.recipient = recipient;
        this.channel = channel;
        this.subject = subject;
        this.body = body;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.status = NotificationStatus.PENDING;
    }

    /**
     * Creates a new notification in {@link NotificationStatus#PENDING}.
     *
     * @param subject optional; channels such as SMS have none
     */
    public static Notification request(String recipient, Channel channel, String subject, String body, Instant now) {
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return new Notification(
                UUID.randomUUID(),
                requireText(recipient, "recipient"),
                channel,
                subject == null || subject.isBlank() ? null : subject,
                requireText(body, "body"),
                now);
    }

    /**
     * Rebuilds a notification from stored state. Meant for persistence adapters only: it trusts the
     * given values and applies no lifecycle rules.
     */
    public static Notification restore(
            UUID id,
            String recipient,
            Channel channel,
            String subject,
            String body,
            NotificationStatus status,
            String failureReason,
            Instant createdAt,
            Instant updatedAt) {
        Notification notification = new Notification(
                Objects.requireNonNull(id, "id must not be null"), recipient, channel, subject, body, createdAt);
        notification.status = Objects.requireNonNull(status, "status must not be null");
        notification.failureReason = failureReason;
        notification.updatedAt = updatedAt;
        return notification;
    }

    public void markQueued(Instant now) {
        transitionTo(NotificationStatus.QUEUED, now);
    }

    public void markDelivered(Instant now) {
        transitionTo(NotificationStatus.DELIVERED, now);
    }

    public void markFailed(String reason, Instant now) {
        String checkedReason = requireText(reason, "reason");
        transitionTo(NotificationStatus.FAILED, now);
        this.failureReason = checkedReason;
    }

    private void transitionTo(NotificationStatus next, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (!status.canTransitionTo(next)) {
            throw new IllegalStatusTransitionException(id, status, next);
        }
        this.status = next;
        this.updatedAt = now;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public UUID id() {
        return id;
    }

    public String recipient() {
        return recipient;
    }

    public Channel channel() {
        return channel;
    }

    public Optional<String> subject() {
        return Optional.ofNullable(subject);
    }

    public String body() {
        return body;
    }

    public NotificationStatus status() {
        return status;
    }

    public Optional<String> failureReason() {
        return Optional.ofNullable(failureReason);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
