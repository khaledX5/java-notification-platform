package io.github.khaledx5.notifications.api.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Test double for {@link TracePropagation}: hands out a fixed trace and remembers which traces were
 * resumed.
 */
final class StubTracePropagation implements TracePropagation {

    final List<Map<String, String>> resumed = new ArrayList<>();
    Map<String, String> current = Map.of();

    @Override
    public Map<String, String> capture() {
        return current;
    }

    @Override
    public void resume(Map<String, String> trace, Runnable action) {
        resumed.add(trace);
        action.run();
    }
}
