package io.github.khaledx5.notifications.api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.khaledx5.notifications.api.domain.Channel;
import io.github.khaledx5.notifications.api.domain.MissingTemplateVariableException;
import io.github.khaledx5.notifications.api.domain.Notification;
import io.github.khaledx5.notifications.api.domain.NotificationRepository;
import io.github.khaledx5.notifications.api.domain.NotificationStatus;
import io.github.khaledx5.notifications.api.domain.Template;
import io.github.khaledx5.notifications.api.domain.TemplateRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-10T09:00:00.123456789Z");

    private final InMemoryNotificationRepository repository = new InMemoryNotificationRepository();
    private final Map<String, Template> storedTemplates = new HashMap<>();
    private final RecordingOutbox outbox = new RecordingOutbox();
    private final StubTracePropagation tracing = new StubTracePropagation();
    private final RecordingMetrics metrics = new RecordingMetrics();
    private final NotificationService service = new NotificationService(
            repository, new InMemoryTemplateRepository(), outbox, tracing, metrics, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void requestedNotificationIsStoredAsQueuedWithItsEventInTheOutbox() {
        Notification notification =
                service.request("dina@example.com", Channel.EMAIL, "Order shipped", "Your order is on its way");

        assertThat(notification.status()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(repository.findById(notification.id())).containsSame(notification);
        assertThat(outbox.added).singleElement().satisfies(event -> {
            assertThat(event.id()).isNotNull();
            assertThat(event.notificationId()).isEqualTo(notification.id());
            assertThat(event.recipient()).isEqualTo("dina@example.com");
            assertThat(event.channel()).isEqualTo(Channel.EMAIL);
            assertThat(event.subject()).isEqualTo("Order shipped");
            assertThat(event.body()).isEqualTo("Your order is on its way");
            assertThat(event.occurredAt()).isEqualTo(notification.updatedAt());
        });
    }

    @Test
    void notificationIsAlreadyStoredAsQueuedWhenItsEventIsAdded() {
        service.request("+201000000000", Channel.SMS, null, "Code 4821");

        assertThat(outbox.storedStatusWhenAdded).containsExactly(NotificationStatus.QUEUED);
    }

    @Test
    void eventOfANotificationWithoutSubjectHasNone() {
        service.request("+201000000000", Channel.SMS, " ", "Code 4821");

        assertThat(outbox.added)
                .singleElement()
                .satisfies(event -> assertThat(event.subject()).isNull());
    }

    @Test
    void everyNotificationGetsItsOwnEvent() {
        service.request("+201000000000", Channel.SMS, null, "Code 4821");
        service.request("+201000000000", Channel.SMS, null, "Code 4821");

        assertThat(outbox.added).extracting(OutboxEvent::id).doesNotHaveDuplicates();
        assertThat(outbox.added).extracting(OutboxEvent::notificationId).doesNotHaveDuplicates();
    }

    @Test
    void eventKeepsTheTraceOfTheRequestThatCausedIt() {
        tracing.current = Map.of("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        service.request("+201000000000", Channel.SMS, null, "Code 4821");

        assertThat(outbox.added)
                .singleElement()
                .satisfies(event -> assertThat(event.trace())
                        .containsEntry("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));
    }

    @Test
    void acceptedNotificationsAreCountedByChannel() {
        service.request("+201000000000", Channel.SMS, null, "Code 4821");
        service.request("dina@example.com", Channel.EMAIL, "Order shipped", "Your order is on its way");

        assertThat(metrics.accepted).containsExactly(Channel.SMS, Channel.EMAIL);
    }

    @Test
    void timestampsAreCutToMilliseconds() {
        Notification notification = service.request("+201000000000", Channel.SMS, null, "Code 4821");

        assertThat(notification.createdAt()).isEqualTo(Instant.parse("2026-01-10T09:00:00.123Z"));
        assertThat(notification.updatedAt()).isEqualTo(notification.createdAt());
    }

    @Test
    void invalidRequestIsRejectedAndNothingIsStored() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.request(" ", Channel.SMS, null, "Code 4821"));

        assertThat(repository.size()).isZero();
        assertThat(outbox.added).isEmpty();
        assertThat(metrics.accepted).isEmpty();
    }

    @Test
    void findsAStoredNotificationById() {
        Notification notification = service.request("+201000000000", Channel.SMS, null, "Code 4821");

        assertThat(service.getById(notification.id())).isSameAs(notification);
    }

    @Test
    void unknownIdIsReportedAsNotFound() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> service.getById(unknown))
                .isInstanceOf(NotificationNotFoundException.class)
                .hasMessageContaining(unknown.toString());
    }

    @Test
    void notificationFromATemplateGetsTheRenderedSubjectAndBody() {
        storedTemplates.put("order-shipped", new Template("order-shipped", "Order {{orderId}} shipped", "Hi {{name}}"));

        Notification notification = service.requestFromTemplate(
                "dina@example.com", Channel.EMAIL, "order-shipped", Map.of("orderId", "A-1042", "name", "Dina"));

        assertThat(notification.subject()).contains("Order A-1042 shipped");
        assertThat(notification.body()).isEqualTo("Hi Dina");
        assertThat(notification.status()).isEqualTo(NotificationStatus.QUEUED);
        assertThat(repository.findById(notification.id())).containsSame(notification);
        assertThat(outbox.added).singleElement().satisfies(event -> {
            assertThat(event.notificationId()).isEqualTo(notification.id());
            assertThat(event.subject()).isEqualTo("Order A-1042 shipped");
            assertThat(event.body()).isEqualTo("Hi Dina");
        });
    }

    @Test
    void unknownTemplateIsRejectedAndNothingIsStored() {
        assertThatThrownBy(() ->
                        service.requestFromTemplate("dina@example.com", Channel.EMAIL, "no-such-template", Map.of()))
                .isInstanceOf(UnknownTemplateException.class)
                .hasMessageContaining("no-such-template");

        assertThat(repository.size()).isZero();
        assertThat(outbox.added).isEmpty();
    }

    @Test
    void missingTemplateVariableIsRejectedAndNothingIsStored() {
        storedTemplates.put("login-code", new Template("login-code", null, "Code {{code}}"));

        assertThatThrownBy(() -> service.requestFromTemplate("+201000000000", Channel.SMS, "login-code", Map.of()))
                .isInstanceOf(MissingTemplateVariableException.class);

        assertThat(repository.size()).isZero();
        assertThat(outbox.added).isEmpty();
    }

    /** The service only ever adds to the outbox; taking events out of it is the job of the relay. */
    private final class RecordingOutbox implements Outbox {

        private final List<OutboxEvent> added = new ArrayList<>();
        private final List<NotificationStatus> storedStatusWhenAdded = new ArrayList<>();

        @Override
        public void add(OutboxEvent event) {
            repository
                    .findById(event.notificationId())
                    .map(Notification::status)
                    .ifPresent(storedStatusWhenAdded::add);
            added.add(event);
        }

        @Override
        public Optional<OutboxEvent> claimNext(Instant now, Duration claimDuration) {
            throw new UnsupportedOperationException("the service does not publish events");
        }

        @Override
        public void markPublished(UUID eventId, Instant now) {
            throw new UnsupportedOperationException("the service does not publish events");
        }

        @Override
        public long countWaiting() {
            return added.size();
        }
    }

    private final class InMemoryTemplateRepository implements TemplateRepository {

        @Override
        public Template save(Template template) {
            storedTemplates.put(template.key(), template);
            return template;
        }

        @Override
        public Optional<Template> findByKey(String key) {
            return Optional.ofNullable(storedTemplates.get(key));
        }
    }

    private static final class InMemoryNotificationRepository implements NotificationRepository {

        private final Map<UUID, Notification> stored = new HashMap<>();

        @Override
        public Notification save(Notification notification) {
            stored.put(notification.id(), notification);
            return notification;
        }

        @Override
        public Optional<Notification> findById(UUID id) {
            return Optional.ofNullable(stored.get(id));
        }

        int size() {
            return stored.size();
        }
    }
}
