package io.github.khaledx5.notifications.api.application;

import io.github.khaledx5.notifications.api.domain.Channel;
import io.github.khaledx5.notifications.api.domain.NotificationStatus;
import java.util.ArrayList;
import java.util.List;

/** Test double for {@link NotificationMetrics} that remembers what was reported. */
final class RecordingMetrics implements NotificationMetrics {

    final List<Channel> accepted = new ArrayList<>();
    final List<NotificationStatus> completed = new ArrayList<>();
    int replays;
    int eventsPublished;
    int eventPublishFailures;

    @Override
    public void accepted(Channel channel) {
        accepted.add(channel);
    }

    @Override
    public void replayed() {
        replays++;
    }

    @Override
    public void eventPublished() {
        eventsPublished++;
    }

    @Override
    public void eventPublishFailed() {
        eventPublishFailures++;
    }

    @Override
    public void completed(NotificationStatus finalStatus) {
        completed.add(finalStatus);
    }
}
