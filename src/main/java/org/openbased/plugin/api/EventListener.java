package org.openbased.plugin.api;

import java.time.Instant;
import java.util.Map;

/** Receives every server event (see {@code WS /api/v1/events} for the event types). */
public interface EventListener {

    void onEvent(String type, Instant timestamp, Map<String, Object> data);
}
