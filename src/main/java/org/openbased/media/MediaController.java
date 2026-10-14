package org.openbased.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.criteria.Predicate;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.openbased.common.ApiException;
import org.openbased.common.PageResponse;
import org.openbased.progress.ProgressLookup;
import org.openbased.common.Paging;
import org.openbased.security.AccessService;
import org.openbased.security.Scopes;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Media")
public class MediaController {

    private final MediaItemRepository items;
    private final MediaFileRepository files;
    private final MediaLookup lookup;
    private final AccessService access;
    private final ProgressLookup progress;

    public MediaController(MediaItemRepository items, MediaFileRepository files, MediaLookup lookup,
            AccessService access, ProgressLookup progress) {
        this.items = items;
        this.files = files;
        this.lookup = lookup;
        this.access = access;
        this.progress = progress;
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
        org.springframework.data.domain.Page<MediaItem> result = items.findAll(spec, Paging.of(page, pageSize, sort(sort)));
        Map<String, MediaDtos.UserProgress> progressById =
                progress.forCurrentUser(result.getContent().stream().map(MediaItem::getId).toList());
        return PageResponse.of(result, m -> MediaDtos.MediaSummary.of(m, progressById.get(m.getId())));
    }

    @GetMapping("/media/{mediaId}")
    @Operation(summary = "Get a media item")
    public MediaDtos.MediaDetail get(@PathVariable String mediaId) {
        access.require(Scopes.MEDIA_READ);
        MediaItem item = lookup.media(mediaId);
        return MediaDtos.MediaDetail.of(item, files.findByMediaIdOrderByIdAsc(item.getId()),
                progress.forCurrentUser(List.of(item.getId())).get(item.getId()));
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

    @GetMapping("/media/{mediaId}/download")
    @Operation(summary = "Download the original media file; supports Range, If-Range and conditional requests",
            description = "Requires media.download. This is a policy for well-behaved clients, not protection: "
                    + "anyone with media.stream can read the whole file through /stream with Range requests.")
    public ResponseEntity<Resource> download(@PathVariable String mediaId,
            @RequestParam(required = false) String fileId, WebRequest request) throws IOException {
        access.require(Scopes.MEDIA_DOWNLOAD);
        MediaFile file = lookup.file(lookup.media(mediaId), fileId);
        Path path = Path.of(file.getPath());
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        long size = attributes.size();
        // Second precision, because Last-Modified and If-Range dates have no more.
        long lastModified = attributes.lastModifiedTime().toMillis() / 1000 * 1000;
        String etag = etag(size, attributes.lastModifiedTime().toMillis());

        if (request.checkNotModified(etag, lastModified)) {
            // 304 Not Modified; the status and headers are already set.
            return null;
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(MediaLookup.contentType(file.getContainer())))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(path.getFileName().toString()))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .eTag(etag)
                .lastModified(lastModified);
        String ifRange = request.getHeader(HttpHeaders.IF_RANGE);
        if (request.getHeader(HttpHeaders.RANGE) != null && ifRange != null
                && !ifRangeMatches(ifRange, etag, lastModified)) {
            // The client's partial copy is of another version: send the whole file. An InputStreamResource
            // keeps Spring from applying the Range header.
            return response.contentLength(size).body(new InputStreamResource(Files.newInputStream(path)));
        }
        return response.body(new FileSystemResource(path));
    }

    /**
     * {@code attachment} with an ASCII {@code filename} for old clients and the exact name in {@code filename*}
     * (RFC 6266). Spring's builder RFC 2047-encodes {@code filename}, which RFC 6266 does not allow.
     */
    static String contentDisposition(String filename) {
        StringBuilder ascii = new StringBuilder();
        StringBuilder encoded = new StringBuilder();
        filename.codePoints().forEach(c -> ascii.append(
                c >= 0x20 && c < 0x7f && c != '"' && c != '\\' ? (char) c : '_'));
        for (byte b : filename.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$&+-.^_`|~".indexOf(c) >= 0) {
                encoded.append(c);
            } else {
                encoded.append('%').append(String.format("%02X", (int) c));
            }
        }
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    /** A strong validator from size and modification time, which change whenever the file is replaced. */
    static String etag(long size, long lastModifiedMillis) {
        return "\"" + Long.toHexString(size) + "-" + Long.toHexString(lastModifiedMillis) + "\"";
    }

    /** RFC 9110 13.1.5: an entity tag must match strongly; a date must equal Last-Modified exactly. */
    static boolean ifRangeMatches(String ifRange, String etag, long lastModifiedMillis) {
        String value = ifRange.trim();
        if (value.startsWith("\"") || value.startsWith("W/")) {
            return value.equals(etag);
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                    == lastModifiedMillis;
        } catch (DateTimeParseException e) {
            return false;
        }
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
