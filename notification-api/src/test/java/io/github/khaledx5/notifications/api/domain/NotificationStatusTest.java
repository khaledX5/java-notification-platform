package io.github.khaledx5.notifications.api.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class NotificationStatusTest {

    @ParameterizedTest
    @CsvSource({"PENDING, QUEUED", "PENDING, FAILED", "QUEUED, DELIVERED", "QUEUED, FAILED"})
    void allowsForwardTransitions(NotificationStatus from, NotificationStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "PENDING, PENDING",
        "PENDING, DELIVERED",
        "QUEUED, PENDING",
        "QUEUED, QUEUED",
        "DELIVERED, FAILED",
        "FAILED, QUEUED"
    })
    void rejectsSkippedRepeatedAndBackwardTransitions(NotificationStatus from, NotificationStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(names = {"DELIVERED", "FAILED"})
    void deliveredAndFailedAreTerminal(NotificationStatus status) {
        assertThat(status.isTerminal()).isTrue();
        assertThat(NotificationStatus.values()).noneMatch(status::canTransitionTo);
    }

    @ParameterizedTest
    @EnumSource(names = {"PENDING", "QUEUED"})
    void pendingAndQueuedAreNotTerminal(NotificationStatus status) {
        assertThat(status.isTerminal()).isFalse();
    }
}
