package org.openbased.download;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.openbased.common.PageResponse;
import org.openbased.common.Paging;
import org.openbased.job.Job;
import org.openbased.job.JobService;
import org.openbased.media.FileResponses;
import org.openbased.playback.PlaybackMode;
import org.openbased.playback.PlaybackPlan;
import org.openbased.playback.Transcoder;
import org.openbased.security.AccessService;
import org.openbased.security.Scopes;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

@RestController
@RequestMapping("/api/v1/downloads")
@Tag(name = "Downloads")
public class DownloadController {

    private final DownloadService service;
    private final JobService jobs;
    private final AccessService access;

    public DownloadController(DownloadService service, JobService jobs, AccessService access) {
        this.service = service;
        this.jobs = jobs;
        this.access = access;
    }

    public record Capabilities(Set<String> containers, Set<String> videoCodecs, Set<String> audioCodecs) {
    }

    /**
     * {@code capabilities} as for playback sessions. {@code maxHeight} (pixels) and {@code maxVideoBitrate}
     * (bits per second) make the server transcode files that exceed them.
     */
    public record CreateDownloadRequest(@NotBlank String mediaId, String fileId, Capabilities capabilities,
            @Min(144) @Max(4320) Integer maxHeight, @Min(100_000) Long maxVideoBitrate) {
    }

    /**
     * {@code progress} (0–100) is set while pending and when ready. {@code url} is set when ready; for
     * {@code DIRECT_PLAY} it serves the original, like {@code /api/v1/media/{id}/download}.
     */
    public record DownloadResponse(String id, String mediaId, String fileId, DownloadStatus status, PlaybackMode mode,
            String container, String videoCodec, String audioCodec, Integer maxHeight, Long maxVideoBitrate,
            Integer progress, Long size, String error, Instant createdAt, Instant readyAt, Instant expiresAt,
            String url) {
    }

    @PostMapping
    @Operation(summary = "Prepare a download: the original if the client can play it within the limits, otherwise "
            + "a remuxed or transcoded copy made by a background job")
    public ResponseEntity<DownloadResponse> create(@Valid @RequestBody CreateDownloadRequest request) {
        access.require(Scopes.MEDIA_DOWNLOAD);
        Capabilities c = request.capabilities();
        PlaybackPlan.Capabilities caps = c == null ? null
                : PlaybackPlan.Capabilities.normalize(c.containers(), c.videoCodecs(), c.audioCodecs());
        DownloadService.Created created = service.create(access.user(), request.mediaId(), request.fileId(), caps,
                new Transcoder.Limits(request.maxHeight(), request.maxVideoBitrate()));
        return ResponseEntity.status(created.existing() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response(created.download()));
    }

    @GetMapping
    @Operation(summary = "List the current user's downloads, newest first")
    public PageResponse<DownloadResponse> list(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        access.require(Scopes.MEDIA_DOWNLOAD);
        return PageResponse.of(service.list(access.user(),
                Paging.of(page, pageSize, Sort.by(Sort.Direction.DESC, "createdAt", "id"))), this::response);
    }

    @GetMapping("/{downloadId}")
    @Operation(summary = "Get a download's status")
    public DownloadResponse get(@PathVariable String downloadId) {
        access.require(Scopes.MEDIA_DOWNLOAD);
        return response(service.find(access.user(), downloadId));
    }

    @GetMapping("/{downloadId}/file")
    @Operation(summary = "Download a ready file; supports Range, If-Range and conditional requests")
    public ResponseEntity<Resource> file(@PathVariable String downloadId, WebRequest request) throws IOException {
        access.require(Scopes.MEDIA_DOWNLOAD);
        DownloadService.ServedFile file = service.file(service.find(access.user(), downloadId));
        return FileResponses.attachment(file.path(), file.filename(), file.contentType(), request);
    }

    @DeleteMapping("/{downloadId}")
    @Operation(summary = "Cancel a pending download, or delete a prepared file")
    public ResponseEntity<Void> delete(@PathVariable String downloadId) {
        access.require(Scopes.MEDIA_DOWNLOAD);
        service.delete(access.user(), downloadId);
        return ResponseEntity.noContent().build();
    }

    private DownloadResponse response(Download d) {
        Integer progress = switch (d.getStatus()) {
            case READY -> 100;
            case PENDING -> d.getJobId() == null ? 0 : jobs.find(d.getJobId()).map(Job::getProgress).orElse(0);
            default -> null;
        };
        String url = d.getStatus() == DownloadStatus.READY ? "/api/v1/downloads/" + d.getId() + "/file" : null;
        return new DownloadResponse(d.getId(), d.getMediaId(), d.getFileId(), d.getStatus(), d.getMode(),
                d.getContainer(), d.getVideoCodec(), d.getAudioCodec(), d.getMaxHeight(), d.getMaxVideoBitrate(),
                progress, d.getSize(), d.getError(), d.getCreatedAt(), d.getReadyAt(), d.getExpiresAt(), url);
    }
}
