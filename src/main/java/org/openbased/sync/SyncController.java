package org.openbased.sync;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.openbased.common.Paging;
import org.openbased.security.AccessService;
import org.openbased.security.Scopes;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Media")
public class SyncController {

    /**
     * {@code syncedAt} lies this far in the past, so changes committed while a sync runs are seen by the next one.
     * Items may therefore be sent twice, which is harmless.
     */
    static final Duration MARGIN = Duration.ofMinutes(1);

    private final MediaTombstoneRepository tombstones;
    private final SyncResetRepository resets;
    private final SyncService sync;
    private final AccessService access;

    public SyncController(MediaTombstoneRepository tombstones, SyncResetRepository resets, SyncService sync,
            AccessService access) {
        this.tombstones = tombstones;
        this.resets = resets;
        this.sync = sync;
        this.access = access;
    }

    public record RemovedItem(String mediaId, Instant removedAt) {
    }

    /**
     * Media removed at or after {@code since}, in libraries the caller can see. {@code resetRequired} means the
     * caller's catalogue changed in a way these lists cannot describe (library access changed, a library was
     * deleted, administrator access changed, or {@code since} is older than {@code openbased.sync.retention}):
     * sync everything again. Pass {@code syncedAt} as {@code since} and {@code updatedSince} next time.
     */
    public record RemovedResponse(List<RemovedItem> items, int page, int pageSize, long total,
            boolean resetRequired, Instant syncedAt) {
    }

    @GetMapping("/media/removed")
    @Operation(summary = "Media removed since a time, for incremental sync together with GET /media?updatedSince=")
    public RemovedResponse removed(@RequestParam Instant since, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        access.require(Scopes.MEDIA_READ);
        Instant syncedAt = Instant.now().minus(MARGIN);
        String userId = access.user().getId();
        if (since.isBefore(sync.horizon()) || resets.existsSince(userId, since)) {
            return new RemovedResponse(List.of(), page == null ? 0 : page, pageSize == null ? 50 : pageSize, 0, true,
                    syncedAt);
        }
        Set<String> libraries = access.accessibleLibraryIds();
        Page<MediaTombstone> result = tombstones.findByLibraryIdInAndRemovedAtGreaterThanEqual(
                libraries.isEmpty() ? Set.of("") : libraries, since,
                Paging.of(page, pageSize, Sort.by("removedAt", "mediaId")));
        return new RemovedResponse(result.getContent().stream()
                .map(t -> new RemovedItem(t.getMediaId(), t.getRemovedAt())).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), false, syncedAt);
    }
}
