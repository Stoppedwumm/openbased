package org.openbased.progress;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.openbased.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openbased.common.PageResponse;
import org.openbased.common.Paging;
import org.openbased.media.MediaItem;
import org.openbased.media.MediaLookup;
import org.openbased.security.AccessService;
import org.openbased.security.Scopes;
import org.openbased.user.User;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Playback Progress")
public class ProgressController {

    private static final Logger log = LoggerFactory.getLogger(ProgressController.class);

    /** At most this many updates are accepted in one batch. */
    static final int MAX_BATCH = 500;

    private final PlaybackProgressRepository progress;
    private final WatchHistoryRepository history;
    private final ProgressService service;
    private final MediaLookup lookup;
    private final AccessService access;

    public ProgressController(PlaybackProgressRepository progress, WatchHistoryRepository history,
            ProgressService service, MediaLookup lookup, AccessService access) {
        this.progress = progress;
        this.history = history;
        this.service = service;
        this.lookup = lookup;
        this.access = access;
    }

    /**
     * Positions and durations are in seconds. {@code recordedAt} is when the client recorded the progress; send
     * it for updates that were queued while offline. Updates older than the stored progress are not applied.
     */
    public record ProgressUpdate(@NotNull @PositiveOrZero Double position, @PositiveOrZero Double duration,
            Boolean completed, Instant recordedAt) {

        ProgressService.Update toUpdate() {
            return new ProgressService.Update(position, duration, completed, recordedAt);
        }
    }

    /** {@code applied} is false when a newer update was already stored; the stored values are returned. */
    public record ProgressSaved(String mediaId, double position, boolean completed, Instant updatedAt,
            boolean applied) {

        static ProgressSaved of(ProgressService.Result r) {
            return new ProgressSaved(r.mediaId(), r.position(), r.completed(), r.updatedAt(), r.applied());
        }
    }

    public record ProgressResponse(double position, Double duration, boolean completed, Instant updatedAt) {
    }

    public record HistoryItem(String mediaId, Instant watchedAt, boolean completed) {
    }

    public record ContinueItem(String mediaId, double position, Double duration, Instant updatedAt) {
    }

    public record BatchItem(@NotBlank String mediaId, @NotNull @PositiveOrZero Double position,
            @PositiveOrZero Double duration, Boolean completed, Instant recordedAt) {
    }

    public record BatchRequest(@NotNull @Size(max = MAX_BATCH) List<@NotNull @Valid BatchItem> updates) {
    }

    public enum BatchStatus {
        APPLIED,
        IGNORED,
        FAILED
    }

    /**
     * One result per update, in request order. {@code position}, {@code completed} and {@code updatedAt} are the
     * stored progress after the update; {@code error} and {@code message} are set when it failed.
     */
    public record BatchResult(String mediaId, BatchStatus status, Double position, Boolean completed,
            Instant updatedAt, String error, String message) {
    }

    public record BatchResponse(List<BatchResult> results) {
    }

    @PutMapping("/media/{mediaId}/progress")
    @Operation(summary = "Update playback progress (seconds); older updates than the stored progress are ignored")
    public ProgressSaved update(@PathVariable String mediaId, @Valid @RequestBody ProgressUpdate request) {
        access.require(Scopes.HISTORY_WRITE);
        return ProgressSaved.of(service.record(access.user(), mediaId, request.toUpdate()));
    }

    @PostMapping("/progress/batch")
    @Operation(summary = "Apply queued progress updates, e.g. from offline playback; each update succeeds or fails "
            + "on its own")
    public BatchResponse batch(@Valid @RequestBody BatchRequest request) {
        access.require(Scopes.HISTORY_WRITE);
        User user = access.user();
        // Apply in the order the updates were recorded, so the newest one for a media item wins.
        Instant now = Instant.now();
        List<Integer> order = new ArrayList<>(IntStream.range(0, request.updates().size()).boxed().toList());
        order.sort(Comparator.comparing(i -> ProgressService.effectiveTime(request.updates().get(i).recordedAt(), now)));
        BatchResult[] results = new BatchResult[order.size()];
        for (int i : order) {
            BatchItem u = request.updates().get(i);
            try {
                ProgressService.Result r = service.record(user, u.mediaId(),
                        new ProgressService.Update(u.position(), u.duration(), u.completed(), u.recordedAt()));
                results[i] = new BatchResult(r.mediaId(), r.applied() ? BatchStatus.APPLIED : BatchStatus.IGNORED,
                        r.position(), r.completed(), r.updatedAt(), null, null);
            } catch (ApiException e) {
                results[i] = new BatchResult(u.mediaId(), BatchStatus.FAILED, null, null, null, e.error(),
                        e.getMessage());
            } catch (RuntimeException e) {
                // Report it on the item: earlier items are already committed, so failing the whole request would
                // leave the client unable to tell what was stored.
                log.error("Progress update for {} in a batch failed", u.mediaId(), e);
                results[i] = new BatchResult(u.mediaId(), BatchStatus.FAILED, null, null, null, "INTERNAL_ERROR",
                        "An unexpected error occurred.");
            }
        }
        return new BatchResponse(List.of(results));
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
