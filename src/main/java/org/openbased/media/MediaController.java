package org.openbased.media;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jakarta.persistence.criteria.Predicate;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.openbased.common.ApiException;
import org.openbased.common.PageResponse;
import org.openbased.common.Paging;
import org.openbased.security.AccessService;
import org.openbased.security.Scopes;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Media")
public class MediaController {

    private final MediaItemRepository items;
    private final MediaFileRepository files;
    private final MediaLookup lookup;
    private final AccessService access;

    public MediaController(MediaItemRepository items, MediaFileRepository files, MediaLookup lookup,
            AccessService access) {
        this.items = items;
        this.files = files;
        this.lookup = lookup;
        this.access = access;
    }

    @GetMapping("/media")
    @Operation(summary = "List media items in libraries visible to the current user")
    public PageResponse<MediaDtos.MediaSummary> list(
            @RequestParam(required = false) String library,
            @RequestParam(required = false) MediaType type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String genre) {
        access.require(Scopes.MEDIA_READ);
        Set<String> libraryIds = library != null ? Set.of(access.library(library).getId())
                : access.accessibleLibraryIds();
        Specification<MediaItem> spec = filter(libraryIds, type, query, year, genre);
        return PageResponse.of(items.findAll(spec, Paging.of(page, pageSize, sort(sort))), MediaDtos.MediaSummary::of);
    }

    @GetMapping("/media/{mediaId}")
    @Operation(summary = "Get a media item")
    public MediaDtos.MediaDetail get(@PathVariable String mediaId) {
        access.require(Scopes.MEDIA_READ);
        MediaItem item = lookup.media(mediaId);
        return MediaDtos.MediaDetail.of(item, files.findByMediaIdOrderByIdAsc(item.getId()));
    }

    @GetMapping("/media/{mediaId}/stream")
    @Operation(summary = "Stream the original media file; supports HTTP Range requests")
    public ResponseEntity<Resource> stream(@PathVariable String mediaId,
            @RequestParam(required = false) String fileId) {
        access.require(Scopes.MEDIA_STREAM);
        MediaFile file = lookup.file(lookup.media(mediaId), fileId);
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(MediaLookup.contentType(file.getContainer())))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(new FileSystemResource(Path.of(file.getPath())));
    }

    static Specification<MediaItem> filter(Set<String> libraryIds, MediaType type, String query, Integer year,
            String genre) {
        return (root, q, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(libraryIds.isEmpty() ? cb.disjunction() : root.get("libraryId").in(libraryIds));
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (year != null) {
                predicates.add(cb.equal(root.get("year"), year));
            }
            if (query != null && !query.isBlank()) {
                String like = "%" + escapeLike(query.trim().toLowerCase(java.util.Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), like, '\\'),
                        cb.like(cb.lower(root.get("originalTitle")), like, '\\'),
                        cb.like(cb.lower(root.get("seriesTitle")), like, '\\')));
            }
            if (genre != null && !genre.isBlank()) {
                q.distinct(true);
                predicates.add(cb.equal(cb.lower(root.join("genres")), genre.toLowerCase(java.util.Locale.ROOT)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Parses {@code sort=title}, {@code sort=-year}, {@code sort=addedAt} etc. */
    static Sort sort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by("sortTitle").and(Sort.by("seasonNumber", "episodeNumber", "id"));
        }
        boolean descending = sort.startsWith("-");
        String field = descending ? sort.substring(1) : sort;
        String property = switch (field) {
            case "title" -> "sortTitle";
            case "year" -> "year";
            case "addedAt", "added" -> "addedAt";
            case "updatedAt" -> "updatedAt";
            case "duration" -> "duration";
            default -> throw ApiException.badRequest("Unsupported sort field: " + field);
        };
        Sort s = Sort.by(descending ? Sort.Direction.DESC : Sort.Direction.ASC, property);
        return s.and(Sort.by("id"));
    }
}
