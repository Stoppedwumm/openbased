package org.openbased.progress;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import org.openbased.media.MediaDtos;
import org.openbased.security.AccessService;
import org.openbased.security.Scopes;
import org.springframework.stereotype.Component;

/** Attaches the caller's playback progress to media responses with a single query per page. */
@Component
public class ProgressLookup {

    private final PlaybackProgressRepository progress;
    private final AccessService access;

    public ProgressLookup(PlaybackProgressRepository progress, AccessService access) {
        this.progress = progress;
        this.access = access;
    }

    /** Progress by media ID; empty when the caller has no user or lacks {@code history.read}. */
    public Map<String, MediaDtos.UserProgress> forCurrentUser(Collection<String> mediaIds) {
        if (mediaIds.isEmpty() || access.principal().userId() == null || !access.has(Scopes.HISTORY_READ)) {
            return Map.of();
        }
        return progress.findByUserIdAndMediaIdIn(access.user().getId(), mediaIds).stream()
                .collect(Collectors.toMap(PlaybackProgress::getMediaId,
                        p -> new MediaDtos.UserProgress(p.getPosition(), p.getDuration(), p.isCompleted()),
                        (a, b) -> a));
    }
}
