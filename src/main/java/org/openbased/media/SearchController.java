package org.openbased.media;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.openbased.common.ApiException;
import org.openbased.common.Paging;
import org.openbased.progress.ProgressLookup;
import org.openbased.security.AccessService;
import org.openbased.security.Scopes;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "Search")
public class SearchController {

    private final MediaItemRepository items;
    private final AccessService access;
    private final ProgressLookup progress;

    public SearchController(MediaItemRepository items, AccessService access, ProgressLookup progress) {
        this.items = items;
        this.access = access;
        this.progress = progress;
    }

    @GetMapping
    @Operation(summary = "Search media by title")
    public MediaDtos.SearchResults search(@RequestParam String q,
            @RequestParam(required = false) MediaType type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        access.require(Scopes.MEDIA_READ);
        if (q.isBlank()) {
            throw ApiException.badRequest("q must not be blank");
        }
        Page<MediaItem> result = items.findAll(
                MediaController.filter(access.accessibleLibraryIds(), type, q, null, null),
                Paging.of(page, pageSize, MediaController.sort(null)));
        var progressById = progress.forCurrentUser(result.getContent().stream().map(MediaItem::getId).toList());
        return new MediaDtos.SearchResults(result.getContent().stream()
                .map(m -> MediaDtos.MediaSummary.of(m, progressById.get(m.getId()))).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements());
    }
}
