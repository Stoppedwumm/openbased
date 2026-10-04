package org.openbased.event;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * A server event. {@code libraryId} and {@code userId} are not serialized; they restrict which
 * subscribers may receive the event.
 *
 * @param libraryId when set, only users with access to this library receive the event
 * @param userId when set, only this user (and administrators) receive the event
 */
public record Event(EventType type, Instant timestamp, Map<String, Object> data,
        @JsonIgnore String libraryId, @JsonIgnore String userId) {
}
