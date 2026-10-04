package org.openbased.playback;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PreDestroy;

import org.openbased.common.Ids;
import org.openbased.config.OpenBasedProperties;
import org.openbased.event.EventBus;
import org.openbased.event.EventType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PlaybackSessions {

    private final Map<String, PlaybackSession> sessions = new ConcurrentHashMap<>();
    private final OpenBasedProperties properties;
    private final EventBus events;

    public PlaybackSessions(OpenBasedProperties properties, EventBus events) {
        this.properties = properties;
        this.events = events;
    }

    public PlaybackSession start(String userId, String mediaId, String fileId, PlaybackPlan plan, String deviceName,
            String platform) {
        PlaybackSession session = new PlaybackSession(Ids.prefixed("play"), userId, mediaId, fileId, plan, deviceName,
                platform);
        sessions.put(session.id(), session);
        events.publishForUser(EventType.PLAYBACK_STARTED, userId, Map.of("sessionId", session.id(),
                "mediaId", mediaId, "mode", plan.mode().name()));
        return session;
    }

    /** The session if it exists and belongs to the user. */
    public Optional<PlaybackSession> find(String sessionId, String userId) {
        return Optional.ofNullable(sessions.get(sessionId)).filter(s -> s.userId().equals(userId));
    }

    public void stop(PlaybackSession session) {
        if (sessions.remove(session.id()) != null) {
            session.stopProcesses();
            events.publishForUser(EventType.PLAYBACK_STOPPED, session.userId(), Map.of("sessionId", session.id(),
                    "mediaId", session.mediaId()));
        }
    }

    /** Marks the user's sessions for the media item as active. */
    public void touch(String userId, String mediaId) {
        sessions.values().stream().filter(s -> s.userId().equals(userId) && s.mediaId().equals(mediaId))
                .forEach(PlaybackSession::touch);
    }

    @Scheduled(fixedDelay = 60_000)
    void expireIdle() {
        Instant cutoff = Instant.now().minus(properties.getPlayback().getIdleTimeout());
        sessions.values().stream().filter(s -> s.lastActivity().isBefore(cutoff)).toList().forEach(this::stop);
    }

    @PreDestroy
    void shutdown() {
        sessions.values().forEach(PlaybackSession::stopProcesses);
    }
}
