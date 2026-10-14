package org.openbased.sync;

import java.time.Instant;

import org.openbased.common.Ids;
import org.openbased.config.OpenBasedProperties;
import org.openbased.media.MediaItem;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Keeps the records incremental sync needs: removed media and changes in what users can see. */
@Service
public class SyncService {

    private final MediaTombstoneRepository tombstones;
    private final SyncResetRepository resets;
    private final OpenBasedProperties properties;

    public SyncService(MediaTombstoneRepository tombstones, SyncResetRepository resets,
            OpenBasedProperties properties) {
        this.tombstones = tombstones;
        this.resets = resets;
        this.properties = properties;
    }

    public void removed(MediaItem item) {
        MediaTombstone t = new MediaTombstone();
        t.setMediaId(item.getId());
        t.setLibraryId(item.getLibraryId());
        t.setRemovedAt(Instant.now());
        tombstones.save(t);
    }

    /** Every user must sync again, e.g. after a library's access changed. */
    public void resetEveryone(String reason) {
        reset(null, reason);
    }

    /** The user must sync again, e.g. after their roles changed. */
    public void reset(String userId, String reason) {
        SyncReset r = new SyncReset();
        r.setId(Ids.prefixed("reset"));
        r.setUserId(userId);
        r.setReason(reason);
        r.setCreatedAt(Instant.now());
        resets.save(r);
    }

    /** Records older than {@code openbased.sync.retention} are deleted; older syncs must start over. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    void prune() {
        Instant cutoff = horizon();
        tombstones.deleteByRemovedAtBefore(cutoff);
        resets.deleteByCreatedAtBefore(cutoff);
    }

    public Instant horizon() {
        return Instant.now().minus(properties.getSync().getRetention());
    }
}
