package io.github.khaledx5.notifications.api.infrastructure.observability;

import io.github.khaledx5.notifications.api.application.TracePropagation;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Micrometer adapter for {@link TracePropagation}.
 *
 * <p>The trace is captured as the headers a client would send over HTTP (W3C {@code traceparent}),
 * which is a format every tracing system can read back.
 *
 * <p>Resuming goes through an observation instead of putting a span in scope by hand. The relay runs
 * inside the observation of its scheduled task, and whatever is sent while that one is current would
 * join the trace of the task. A receiver observation takes its parent from the stored headers, and
 * the Kafka send inside it becomes its child.
 */
@Component
class ObservationTracePropagation implements TracePropagation {

    /** Also the name of the timer that measures how long publishing an outbox event takes. */
    static final String OBSERVATION_NAME = "notifications.outbox.publish";

    private final ObservationRegistry observations;
    private final Tracer tracer;
    private final Propagator propagator;

    ObservationTracePropagation(
            ObservationRegistry observations, ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.observations = observations;
        this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
        this.propagator = propagator.getIfAvailable(() -> Propagator.NOOP);
    }

    @Override
    public Map<String, String> capture() {
        TraceContext current = tracer.currentTraceContext().context();
        if (current == null) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        propagator.inject(current, headers, Map::put);
        return headers;
    }

    @Override
    public void resume(Map<String, String> trace, Runnable action) {
        ReceiverContext<Map<String, String>> context = new ReceiverContext<>(Map::get);
        context.setCarrier(trace);
        Observation.createNotStarted(OBSERVATION_NAME, () -> context, observations)
                .observe(action);
    }
}
