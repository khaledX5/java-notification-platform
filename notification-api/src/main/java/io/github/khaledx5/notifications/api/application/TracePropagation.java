package io.github.khaledx5.notifications.api.application;

import java.util.Map;

/**
 * Outgoing port for carrying a trace across the outbox.
 *
 * <p>A trace normally follows the thread that handles a request. The outbox breaks that chain: the
 * event is published later, by the relay, on another thread and possibly another instance. So the
 * trace is captured when the event is stored, kept with it, and picked up again when it is published.
 * A request, its event and its delivery then show up as one trace.
 */
public interface TracePropagation {

    /**
     * The trace the caller is running in, in a form that can be stored.
     *
     * @return empty if the caller is not part of a trace
     */
    Map<String, String> capture();

    /**
     * Runs the action as a continuation of a captured trace. Whatever the action sends out carries
     * that trace on.
     *
     * @param trace what {@link #capture()} returned; an empty map starts a new trace
     */
    void resume(Map<String, String> trace, Runnable action);
}
