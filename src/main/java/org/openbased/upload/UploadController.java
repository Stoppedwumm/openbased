package org.openbased.upload;

import java.io.IOException;
import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.openbased.common.ApiException;
import org.openbased.library.Library;
import org.openbased.security.AccessService;
import org.openbased.security.Scopes;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/uploads")
@Tag(name = "Uploads")
public class UploadController {

    private final UploadService uploads;
    private final AccessService access;

    public UploadController(UploadService uploads, AccessService access) {
        this.uploads = uploads;
        this.access = access;
    }

    public record CreateUploadRequest(@NotBlank String filename, @NotNull @Positive Long size,
            @NotBlank String libraryId) {
    }

    public record UploadResponse(String id, String filename, String libraryId, long size, long received,
            long chunkSize, UploadStatus status, String mediaId, String jobId, String error, Instant createdAt) {
        static UploadResponse of(Upload u) {
            return new UploadResponse(u.getId(), u.getFilename(), u.getLibraryId(), u.getSize(), u.getReceived(),
                    u.getChunkSize(), u.getStatus(), u.getMediaId(), u.getJobId(), u.getError(), u.getCreatedAt());
        }
    }

    public record ChunkResponse(long received, long total) {
    }

    public record CompleteResponse(UploadStatus status, String mediaId, String jobId) {
    }

    @PostMapping
    @Operation(summary = "Create a resumable upload into a library")
    public ResponseEntity<UploadResponse> create(@Valid @RequestBody CreateUploadRequest request) {
        access.require(Scopes.UPLOAD);
        Library library = access.library(request.libraryId());
        Upload upload = uploads.create(access.user().getId(), library, request.filename(), request.size());
        return ResponseEntity.status(HttpStatus.CREATED).body(UploadResponse.of(upload));
    }

    @PatchMapping(value = "/{uploadId}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(summary = "Upload a chunk (Content-Range: bytes start-end/total)")
    public ChunkResponse chunk(@PathVariable String uploadId,
            @RequestHeader(value = "Content-Range", required = false) String contentRange,
            HttpServletRequest request) throws IOException {
        access.require(Scopes.UPLOAD);
        Upload upload = uploads.writeChunk(access.user().getId(), uploadId, contentRange,
                request.getContentLengthLong(), request.getInputStream());
        return new ChunkResponse(upload.getReceived(), upload.getSize());
    }

    @GetMapping("/{uploadId}")
    @Operation(summary = "Get upload status; use 'received' to resume")
    public UploadResponse get(@PathVariable String uploadId) {
        access.require(Scopes.UPLOAD);
        return UploadResponse.of(uploads.get(access.user().getId(), uploadId));
    }

    @PostMapping("/{uploadId}/complete")
    @Operation(summary = "Finish an upload; the file is processed asynchronously")
    public ResponseEntity<CompleteResponse> complete(@PathVariable String uploadId) {
        access.require(Scopes.UPLOAD);
        String userId = access.user().getId();
        Upload upload = uploads.get(userId, uploadId);
        if (!access.canAccessLibrary(upload.getLibraryId())) {
            throw ApiException.forbidden("PERMISSION_DENIED", "You no longer have access to the target library.");
        }
        upload = uploads.complete(userId, uploadId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new CompleteResponse(upload.getStatus(), upload.getMediaId(), upload.getJobId()));
    }

    @DeleteMapping("/{uploadId}")
    @Operation(summary = "Cancel an upload")
    public ResponseEntity<Void> cancel(@PathVariable String uploadId) {
        access.require(Scopes.UPLOAD);
        uploads.cancel(access.user().getId(), uploadId);
        return ResponseEntity.noContent().build();
    }
}
