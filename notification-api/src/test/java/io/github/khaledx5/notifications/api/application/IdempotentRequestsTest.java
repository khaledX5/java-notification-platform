package io.github.khaledx5.notifications.api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import io.github.khaledx5.notifications.api.domain.Channel;
import io.github.khaledx5.notifications.api.domain.Notification;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class IdempotentRequestsTest {

    private static final Instant NOW = Instant.parse("2026-01-10T09:00:00.123Z");
    private static final String KEY = "order-1042-shipped";
    private static final String FINGERPRINT = "3f2a";
    private static final String OTHER_FINGERPRINT = "9c41";

    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
    private final NotificationService notifications = mock(NotificationService.class);
    private final IdempotentRequests requests = new IdempotentRequests(store, notifications);
    private final CountingRequest request = new CountingRequest();

    @Test
    void firstRequestRunsAndItsKeyIsCompletedWithTheNotification() {
        IdempotentRequests.Result result = requests.execute(KEY, FINGERPRINT, request);

        assertThat(result.replayed()).isFalse();
        assertThat(result.notification()).isSameAs(request.created);
        assertThat(store.held(KEY)).contains(IdempotencyRecord.completed(FINGERPRINT, request.created.id()));
    }

    @Test
    void repeatedRequestGetsTheFirstNotificationBackWithoutRunningAgain() {
        Notification first = requests.execute(KEY, FINGERPRINT, request).notification();
        given(notifications.getById(first.id())).willReturn(first);

        IdempotentRequests.Result repeat = requests.execute(KEY, FINGERPRINT, request);

        assertThat(repeat.replayed()).isTrue();
        assertThat(repeat.notification()).isSameAs(first);
        assertThat(request.runs).isEqualTo(1);
    }

    @Test
    void differentKeysAreIndependentRequests() {
        requests.execute(KEY, FINGERPRINT, request);

        IdempotentRequests.Result other = requests.execute("order-1043-shipped", FINGERPRINT, request);

        assertThat(other.replayed()).isFalse();
        assertThat(request.runs).isEqualTo(2);
    }

    @Test
    void keySentAgainWithDifferentContentIsRejectedWithoutRunning() {
        requests.execute(KEY, FINGERPRINT, request);

        assertThatThrownBy(() -> requests.execute(KEY, OTHER_FINGERPRINT, request))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        assertThat(request.runs).isEqualTo(1);
    }

    @Test
    void repeatThatArrivesWhileTheFirstRequestIsStillRunningIsRejected() {
        store.reserve(KEY, FINGERPRINT);

        assertThatThrownBy(() -> requests.execute(KEY, FINGERPRINT, request))
                .isInstanceOf(RequestInProgressException.class);

        assertThat(request.runs).isZero();
    }

    @Test
    void failedRequestReleasesItsKeySoItCanBeSentAgain() {
        IllegalStateException failure = new IllegalStateException("database is down");

        assertThatThrownBy(() -> requests.execute(KEY, FINGERPRINT, failing(failure)))
                .isSameAs(failure);
        assertThat(store.held(KEY)).isEmpty();

        IdempotentRequests.Result secondTry = requests.execute(KEY, FINGERPRINT, request);

        assertThat(secondTry.replayed()).isFalse();
        assertThat(request.runs).isEqualTo(1);
    }

    @Test
    void requestDoesNotRunWhenItsKeyCannotBeChecked() {
        store.failOnReserve = true;

        assertThatThrownBy(() -> requests.execute(KEY, FINGERPRINT, request))
                .isInstanceOf(IdempotencyUnavailableException.class);

        assertThat(request.runs).isZero();
    }

    @Test
    void acceptedNotificationIsReturnedEvenIfItsKeyCannotBeCompleted() {
        store.failOnComplete = true;

        IdempotentRequests.Result result = requests.execute(KEY, FINGERPRINT, request);

        assertThat(result.notification()).isSameAs(request.created);
        assertThat(result.replayed()).isFalse();
    }

    @Test
    void failureOfTheRequestIsWhatTheCallerSeesEvenIfTheKeyCannotBeReleased() {
        store.failOnRelease = true;
        IllegalStateException failure = new IllegalStateException("database is down");

        assertThatThrownBy(() -> requests.execute(KEY, FINGERPRINT, failing(failure)))
                .isSameAs(failure);
    }

    private static Supplier<Notification> failing(RuntimeException failure) {
        return () -> {
            throw failure;
        };
    }

    private static final class CountingRequest implements Supplier<Notification> {

        private int runs;
        private Notification created;

        @Override
        public Notification get() {
            runs++;
            created = Notification.request("+201000000000", Channel.SMS, null, "Code 4821", NOW);
            return created;
        }
    }

    private static final class InMemoryIdempotencyStore implements IdempotencyStore {

        private final Map<String, IdempotencyRecord> records = new HashMap<>();
        private boolean failOnReserve;
        private boolean failOnComplete;
        private boolean failOnRelease;

        Optional<IdempotencyRecord> held(String key) {
            return Optional.ofNullable(records.get(key));
        }

        @Override
        public Optional<IdempotencyRecord> reserve(String key, String fingerprint) {
            failIf(failOnReserve);
            return Optional.ofNullable(records.putIfAbsent(key, IdempotencyRecord.inProgress(fingerprint)));
        }

        @Override
        public void complete(String key, String fingerprint, UUID notificationId) {
            failIf(failOnComplete);
            records.put(key, IdempotencyRecord.completed(fingerprint, notificationId));
        }

        @Override
        public void release(String key) {
            failIf(failOnRelease);
            records.remove(key);
        }

        private static void failIf(boolean unavailable) {
            if (unavailable) {
                throw new IdempotencyUnavailableException("store is down", new IllegalStateException());
            }
        }
    }
}
