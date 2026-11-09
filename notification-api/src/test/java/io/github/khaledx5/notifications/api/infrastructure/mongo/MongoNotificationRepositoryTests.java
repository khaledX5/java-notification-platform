package io.github.khaledx5.notifications.api.infrastructure.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.khaledx5.notifications.api.TestcontainersConfiguration;
import io.github.khaledx5.notifications.api.domain.Channel;
import io.github.khaledx5.notifications.api.domain.Notification;
import io.github.khaledx5.notifications.api.domain.NotificationStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.context.annotation.Import;

@DataMongoTest
@Import({TestcontainersConfiguration.class, MongoNotificationRepository.class})
class MongoNotificationRepositoryTests {

    private static final Instant CREATED = Instant.parse("2026-01-10T09:00:00Z");
    private static final Instant LATER = CREATED.plusSeconds(5);

    @Autowired
    private MongoNotificationRepository repository;

    @Autowired
    private SpringDataNotificationRepository documents;

    @Test
    void storedNotificationComesBackWithTheSameState() {
        Notification email = Notification.request(
                "dina@example.com", Channel.EMAIL, "Order shipped", "Your order is on its way", CREATED);

        repository.save(email);

        assertThat(repository.findById(email.id())).hasValueSatisfying(found -> {
            assertThat(found.id()).isEqualTo(email.id());
            assertThat(found.recipient()).isEqualTo("dina@example.com");
            assertThat(found.channel()).isEqualTo(Channel.EMAIL);
            assertThat(found.subject()).contains("Order shipped");
            assertThat(found.body()).isEqualTo("Your order is on its way");
            assertThat(found.status()).isEqualTo(NotificationStatus.PENDING);
            assertThat(found.failureReason()).isEmpty();
            assertThat(found.createdAt()).isEqualTo(CREATED);
            assertThat(found.updatedAt()).isEqualTo(CREATED);
        });
    }

    @Test
    void notificationWithoutSubjectComesBackWithoutOne() {
        Notification sms = pendingSms();

        repository.save(sms);

        assertThat(repository.findById(sms.id()))
                .hasValueSatisfying(found -> assertThat(found.subject()).isEmpty());
    }

    @Test
    void savingAgainReplacesTheStoredState() {
        Notification sms = pendingSms();
        repository.save(sms);
        long countAfterFirstSave = documents.count();

        sms.markQueued(LATER);
        sms.markFailed("provider rejected the number", LATER.plusSeconds(1));
        repository.save(sms);

        assertThat(documents.count()).isEqualTo(countAfterFirstSave);
        assertThat(repository.findById(sms.id())).hasValueSatisfying(found -> {
            assertThat(found.status()).isEqualTo(NotificationStatus.FAILED);
            assertThat(found.failureReason()).contains("provider rejected the number");
            assertThat(found.createdAt()).isEqualTo(CREATED);
            assertThat(found.updatedAt()).isEqualTo(LATER.plusSeconds(1));
        });
    }

    @Test
    void restoredNotificationKeepsItsLifecycleRules() {
        Notification sms = pendingSms();
        sms.markQueued(LATER);
        repository.save(sms);

        Notification found = repository.findById(sms.id()).orElseThrow();
        found.markDelivered(LATER.plusSeconds(2));

        assertThat(found.status()).isEqualTo(NotificationStatus.DELIVERED);
    }

    @Test
    void unknownIdIsEmpty() {
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }

    private static Notification pendingSms() {
        return Notification.request("+201000000000", Channel.SMS, null, "Code 4821", CREATED);
    }
}
