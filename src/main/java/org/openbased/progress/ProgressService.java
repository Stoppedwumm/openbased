package org.openbased.progress;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.openbased.common.ApiException;
import org.openbased.common.Ids;
import org.openbased.event.EventBus;
import org.openbased.event.EventType;
import org.openbased.media.MediaItem;
import org.openbased.media.MediaLookup;
import org.openbased.playback.PlaybackSessions;
import org.openbased.user.User;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records playback progress and watch history. Updates may carry the time they were recorded on the client
 * (for progress made offline and synced later): that time is clamped between the time the media item was
 * added and the server clock, an update older than the stored progress is not applied, and the history entry is dated
 * with it.
 */
@Service
public class ProgressService {

    /** Progress updates within this window of the previous one belong to the same viewing. */
    static final Duration SAME_VIEWING = Duration.ofHours(6);

    private final PlaybackProgressRepository progress;
    private final WatchHistoryRepository history;
    private final MediaLookup lookup;
    private final PlaybackSessions sessions;
    private final EventBus events;
    private final TransactionTemplate transactions;

    public ProgressService(PlaybackProgressRepository progress, WatchHistoryRepository history, MediaLookup lookup,
            PlaybackSessions sessions, EventBus events, PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.progress = progress;
        this.history = history;
        this.lookup = lookup;
        this.sessions = sessions;
        this.events = events;
    }

    /** Positions and durations are in seconds; {@code recordedAt} is optional. */
    public record Update(double position, Double duration, Boolean completed, Instant recordedAt) {
    }

    /**
     * The progress stored after an update. {@code applied} is false when the update was older than the stored
     * progress; the stored values are returned unchanged in that case.
     */
    public record Result(String mediaId, double position, boolean completed, Instant updatedAt, boolean applied) {
    }

    /** Effective time of an update: the client's time, but never later than the server's clock. */
    static Instant effectiveTime(Instant recordedAt, Instant now) {
        return recordedAt == null || recordedAt.isAfter(now) ? now : recordedAt;
    }

    /**
     * Records an update in its own transaction. The first update for a user and media item inserts the progress
     * row; when two such updates race, one hits the unique constraint and is retried, and then finds the row.
     */
    public Result record(User user, String mediaId, Update update) {
        try {
            return transactions.execute(status -> apply(user, mediaId, update));
        } catch (DataIntegrityViolationException e) {
            return transactions.execute(status -> apply(user, mediaId, update));
        }
    }

    private Result apply(User user, String mediaId, Update update) {
        MediaItem item = lookup.media(mediaId);
        if (update.duration() != null && update.position() > update.duration()) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "position must not exceed duration");
        }
        Instant at = effectiveTime(update.recordedAt(), Instant.now());
        if (item.getAddedAt() != null && at.isBefore(item.getAddedAt())) {
            // Nobody watched the item here before it was added: a device with a reset or lagging clock.
            at = item.getAddedAt();
        }
        boolean completed = update.completed() != null ? update.completed()
                : update.duration() != null && update.duration() > 0 && update.position() / update.duration() >= 0.95;

        // The viewing happened even if newer progress from another device already replaced this position.
        recordHistory(user, item, at, completed);

        Optional<PlaybackProgress> stored = progress.findByUserIdAndMediaId(user.getId(), item.getId());
        if (stored.isPresent() && stored.get().getUpdatedAt() != null && stored.get().getUpdatedAt().isAfter(at)) {
            PlaybackProgress p = stored.get();
            return new Result(item.getId(), p.getPosition(), p.isCompleted(), p.getUpdatedAt(), false);
        }
        PlaybackProgress p = stored.orElseGet(() -> {
            PlaybackProgress created = new PlaybackProgress();
            created.setId(Ids.prefixed("prog"));
            created.setUserId(user.getId());
            created.setMediaId(item.getId());
            return created;
        });
        p.setPosition(update.position());
        if (update.duration() != null) {
            p.setDuration(update.duration());
        }
        p.setCompleted(completed);
        p.setUpdatedAt(at);
        // Flush now, so a concurrent insert of the same row fails here and can be retried.
        progress.saveAndFlush(p);

        sessions.touch(user.getId(), item.getId());
        events.publishForUser(EventType.PLAYBACK_PROGRESS, user.getId(), Map.of("mediaId", item.getId(),
                "position", update.position(), "completed", completed));
        return new Result(item.getId(), p.getPosition(), p.isCompleted(), p.getUpdatedAt(), true);
    }

    /**
     * Extends the viewing that was in progress at {@code at}, or starts a new one. Only viewings at or before
     * {@code at} qualify: progress synced late from an offline device must not change a later viewing, e.g. mark
     * an unfinished viewing on another device as completed.
     */
    private void recordHistory(User user, MediaItem item, Instant at, boolean completed) {
        WatchHistoryEntry entry = history.findFirstByUserIdAndMediaIdAndWatchedAtBetweenOrderByWatchedAtDesc(
                user.getId(), item.getId(), at.minus(SAME_VIEWING), at)
                .filter(h -> !h.isCompleted() || completed)
                .orElseGet(() -> {
                    WatchHistoryEntry created = new WatchHistoryEntry();
                    created.setId(Ids.prefixed("hist"));
                    created.setUserId(user.getId());
                    created.setMediaId(item.getId());
                    return created;
                });
        if (entry.getWatchedAt() == null || entry.getWatchedAt().isBefore(at)) {
            entry.setWatchedAt(at);
        }
        entry.setCompleted(entry.isCompleted() || completed);
        history.save(entry);
    }
}
