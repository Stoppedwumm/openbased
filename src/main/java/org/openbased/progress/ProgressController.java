package org.openbased.progress;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.openbased.common.ApiException;
import org.openbased.common.Ids;
import org.openbased.common.PageResponse;
import org.openbased.common.Paging;
import org.openbased.event.EventBus;
import org.openbased.event.EventType;
import org.openbased.media.MediaItem;
import org.openbased.media.MediaLookup;
import org.openbased.playback.PlaybackSessions;
import org.openbased.security.AccessService;
import org.openbased.security.Scopes;
import org.openbased.user.User;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Playback Progress")
public class ProgressController {

    /** Progress updates within this window of the previous one belong to the same viewing. */
    private static final Duration SAME_VIEWING = Duration.ofHours(6);

    private final PlaybackProgressRepository progress;
    private final WatchHistoryRepository history;
    private final MediaLookup lookup;
    private final PlaybackSessions sessions;
    private final EventBus events;
    private final AccessService access;

    public ProgressController(PlaybackProgressRepository progress, WatchHistoryRepository history, MediaLookup lookup,
            PlaybackSessions sessions, EventBus events, AccessService access) {
        this.progress = progress;
        this.history = history;
        this.lookup = lookup;
        this.sessions = sessions;
        this.events = events;
        this.access = access;
    }

    /** Positions and durations are in seconds. */
    public record ProgressUpdate(@NotNull @PositiveOrZero Double position, @PositiveOrZero Double duration,
            Boolean completed) {
    }

    public record ProgressSaved(String mediaId, double position, boolean completed) {
    }

    public record ProgressResponse(double position, Double duration, boolean completed, Instant updatedAt) {
    }

    public record HistoryItem(String mediaId, Instant watchedAt, boolean completed) {
    }

    public record ContinueItem(String mediaId, double position, Double duration, Instant updatedAt) {
    }

    @PutMapping("/media/{mediaId}/progress")
    @Transactional
    @Operation(summary = "Update playback progress (seconds)")
    public ProgressSaved update(@PathVariable String mediaId, @Valid @RequestBody ProgressUpdate request) {
        access.require(Scopes.HISTORY_WRITE);
        User user = access.user();
        MediaItem item = lookup.media(mediaId);
        if (request.duration() != null && request.position() > request.duration()) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "position must not exceed duration");
        }
        Instant now = Instant.now();
        PlaybackProgress p = progress.findByUserIdAndMediaId(user.getId(), item.getId()).orElseGet(() -> {
            PlaybackProgress created = new PlaybackProgress();
            created.setId(Ids.prefixed("prog"));
            created.setUserId(user.getId());
            created.setMediaId(item.getId());
            return created;
        });
        boolean completed = request.completed() != null ? request.completed()
                : request.duration() != null && request.duration() > 0 && request.position() / request.duration() >= 0.95;
        p.setPosition(request.position());
        if (request.duration() != null) {
            p.setDuration(request.duration());
        }
        p.setCompleted(completed);
        p.setUpdatedAt(now);
        progress.save(p);

        WatchHistoryEntry entry = history.findFirstByUserIdAndMediaIdOrderByWatchedAtDesc(user.getId(), item.getId())
                .filter(h -> h.getWatchedAt().isAfter(now.minus(SAME_VIEWING)))
                .filter(h -> !h.isCompleted() || completed)
                .orElseGet(() -> {
                    WatchHistoryEntry created = new WatchHistoryEntry();
                    created.setId(Ids.prefixed("hist"));
                    created.setUserId(user.getId());
                    created.setMediaId(item.getId());
                    return created;
                });
        entry.setWatchedAt(now);
        entry.setCompleted(entry.isCompleted() || completed);
        history.save(entry);

        sessions.touch(user.getId(), item.getId());
        events.publishForUser(EventType.PLAYBACK_PROGRESS, user.getId(), Map.of("mediaId", item.getId(),
                "position", request.position(), "completed", completed));
        return new ProgressSaved(item.getId(), p.getPosition(), p.isCompleted());
    }

    @GetMapping("/media/{mediaId}/progress")
    @Operation(summary = "Get the current user's progress for a media item")
    public ProgressResponse get(@PathVariable String mediaId) {
        access.require(Scopes.HISTORY_READ);
        User user = access.user();
        MediaItem item = lookup.media(mediaId);
        return progress.findByUserIdAndMediaId(user.getId(), item.getId())
                .map(p -> new ProgressResponse(p.getPosition(), p.getDuration(), p.isCompleted(), p.getUpdatedAt()))
                .orElseThrow(() -> ApiException.notFound("PROGRESS_NOT_FOUND", "No progress recorded for this media item."));
    }

    @GetMapping("/history")
    @Operation(summary = "Get the current user's watch history, newest first")
    public PageResponse<HistoryItem> history(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        access.require(Scopes.HISTORY_READ);
        User user = access.user();
        Set<String> libraries = access.accessibleLibraryIds();
        if (libraries.isEmpty()) {
            return new PageResponse<>(List.of(), page == null ? 0 : page, pageSize == null ? 50 : pageSize, 0);
        }
        return PageResponse.of(history.findVisible(user.getId(), libraries,
                Paging.of(page, pageSize, Sort.by(Sort.Direction.DESC, "watchedAt", "id"))),
                h -> new HistoryItem(h.getMediaId(), h.getWatchedAt(), h.isCompleted()));
    }

    @GetMapping("/continue-watching")
    @Operation(summary = "Media the current user started but has not finished, most recent first")
    public PageResponse<ContinueItem> continueWatching(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        access.require(Scopes.HISTORY_READ);
        User user = access.user();
        Set<String> libraries = access.accessibleLibraryIds();
        if (libraries.isEmpty()) {
            return new PageResponse<>(List.of(), page == null ? 0 : page, pageSize == null ? 50 : pageSize, 0);
        }
        return PageResponse.of(progress.findInProgress(user.getId(), libraries,
                Paging.of(page, pageSize, Sort.by(Sort.Direction.DESC, "updatedAt", "id"))),
                p -> new ContinueItem(p.getMediaId(), p.getPosition(), p.getDuration(), p.getUpdatedAt()));
    }
}
