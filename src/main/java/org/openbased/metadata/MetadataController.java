package org.openbased.metadata;

import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.openbased.common.ApiException;
import org.openbased.job.Job;
import org.openbased.job.JobService;
import org.openbased.media.MediaItem;
import org.openbased.media.MediaLookup;
import org.openbased.media.MediaType;
import org.openbased.security.AccessService;
import org.openbased.security.Scopes;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Metadata")
public class MetadataController {

    private final MetadataService metadata;
    private final MediaLookup lookup;
    private final JobService jobs;
    private final AccessService access;

    public MetadataController(MetadataService metadata, MediaLookup lookup, JobService jobs, AccessService access) {
        this.metadata = metadata;
        this.lookup = lookup;
        this.jobs = jobs;
        this.access = access;
    }

    @GetMapping("/metadata/search")
    @Operation(summary = "Search configured metadata providers")
    public MetadataService.SearchResponse search(@RequestParam MediaType type, @RequestParam String query,
            @RequestParam(required = false) Integer year, @RequestParam(required = false) String provider) {
        access.require(Scopes.MEDIA_READ);
        if (query.isBlank()) {
            throw ApiException.badRequest("query must not be blank");
        }
        return metadata.search(provider, type.name(), query, year);
    }

    @PostMapping("/media/{mediaId}/metadata/refresh")
    @Operation(summary = "Refresh a media item's metadata asynchronously")
    public ResponseEntity<Map<String, Object>> refresh(@PathVariable String mediaId,
            @RequestParam(required = false) String provider) {
        access.require(Scopes.LIBRARY_WRITE);
        MediaItem item = lookup.media(mediaId);
        String providerId = metadata.provider(provider).id();
        Job job = jobs.submit("METADATA_REFRESH", access.principal().userId(), item.getId(), handle -> {
            if (!metadata.refresh(item.getId(), providerId)) {
                throw new IllegalStateException("No metadata match found");
            }
        });
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "QUEUED", "jobId", job.getId()));
    }
}
