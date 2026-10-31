package org.openbased.upload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openbased.common.ApiException;
import org.openbased.common.Ids;
import org.openbased.config.OpenBasedProperties;
import org.openbased.event.EventBus;
import org.openbased.event.EventType;
import org.openbased.job.Job;
import org.openbased.job.JobService;
import org.openbased.library.Library;
import org.openbased.library.LibraryRepository;
import org.openbased.scan.LibraryScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Resumable uploads. Chunks are written into a temporary file at the offset given by
 * {@code Content-Range}; clients resume by asking for the upload's {@code received} count and continuing
 * from there. On completion the file is moved into the library's first folder and scanned.
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    private static final Pattern CONTENT_RANGE = Pattern.compile("^bytes (\\d+)-(\\d+)/(\\d+)$");

    private final UploadRepository uploads;
    private final LibraryRepository libraries;
    private final LibraryScanner scanner;
    private final JobService jobs;
    private final EventBus events;
    private final OpenBasedProperties properties;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public UploadService(UploadRepository uploads, LibraryRepository libraries, LibraryScanner scanner, JobService jobs,
            EventBus events, OpenBasedProperties properties) {
        this.uploads = uploads;
        this.libraries = libraries;
        this.scanner = scanner;
        this.jobs = jobs;
        this.events = events;
        this.properties = properties;
    }

    public Upload create(String userId, Library library, String filename, long size) {
        String name = sanitize(filename);
        String extension = extension(name);
        if (!properties.getUploads().getAllowedExtensions().contains(extension)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                    "Files of type ." + extension + " cannot be uploaded.");
        }
        if (!LibraryScanner.isMediaFile(Path.of(name), library.getType())) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                    "Files of type ." + extension + " do not belong in a " + library.getType() + " library.");
        }
        if (size <= 0) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "size must be positive");
        }
        if (size > properties.getUploads().getMaxSize()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                    "Uploads are limited to " + properties.getUploads().getMaxSize() + " bytes.");
        }
        if (library.getPaths().isEmpty()) {
            throw ApiException.unprocessable("LIBRARY_HAS_NO_PATH", "The library has no folder to upload into.");
        }
        Upload upload = new Upload();
        upload.setId(Ids.prefixed("upload"));
        upload.setUserId(userId);
        upload.setLibraryId(library.getId());
        upload.setFilename(name);
        upload.setSize(size);
        upload.setChunkSize(properties.getUploads().getChunkSize());
        upload.setStatus(UploadStatus.UPLOADING);
        upload.setCreatedAt(Instant.now());
        upload.setUpdatedAt(upload.getCreatedAt());
        try {
            Files.createDirectories(tempDir());
            Files.deleteIfExists(tempFile(upload));
            Files.createFile(tempFile(upload));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create upload file", e);
        }
        uploads.save(upload);
        events.publishForUser(EventType.UPLOAD_STARTED, userId, Map.of("uploadId", upload.getId(),
                "libraryId", library.getId(), "filename", name));
        return upload;
    }

    public Upload get(String userId, String uploadId) {
        return uploads.findById(uploadId).filter(u -> u.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("UPLOAD_NOT_FOUND", "The requested upload does not exist."));
    }

    /**
     * Writes a chunk. A chunk may start anywhere up to the current {@code received} offset, so a chunk
     * whose acknowledgement was lost can simply be sent again.
     */
    public Upload writeChunk(String userId, String uploadId, String contentRange, long contentLength, InputStream body) {
        synchronized (locks.computeIfAbsent(uploadId, k -> new Object())) {
            Upload upload = get(userId, uploadId);
            if (upload.getStatus() != UploadStatus.UPLOADING) {
                throw ApiException.conflict("UPLOAD_NOT_ACTIVE", "The upload is " + upload.getStatus() + ".");
            }
            if (contentRange == null) {
                throw ApiException.badRequest("Content-Range header is required");
            }
            Matcher m = CONTENT_RANGE.matcher(contentRange.trim());
            if (!m.matches()) {
                throw ApiException.badRequest("Content-Range must look like 'bytes start-end/total'");
            }
            long start = Long.parseLong(m.group(1));
            long end = Long.parseLong(m.group(2));
            long total = Long.parseLong(m.group(3));
            long length = end - start + 1;
            if (total != upload.getSize() || end < start || end >= total) {
                throw ApiException.badRequest("Content-Range does not match the upload size " + upload.getSize());
            }
            if (length > upload.getChunkSize()) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                        "Chunks are limited to " + upload.getChunkSize() + " bytes.");
            }
            if (contentLength >= 0 && contentLength != length) {
                throw ApiException.badRequest("Content-Length does not match Content-Range");
            }
            if (start > upload.getReceived()) {
                throw ApiException.conflict("UPLOAD_OFFSET_MISMATCH",
                        "Expected a chunk starting at or before byte " + upload.getReceived() + ".");
            }
            long written = write(tempFile(upload), start, length, body);
            if (written != length) {
                throw ApiException.badRequest("The request body ended after " + written + " of " + length + " bytes.");
            }
            upload.setReceived(Math.max(upload.getReceived(), end + 1));
            upload.setUpdatedAt(Instant.now());
            return uploads.save(upload);
        }
    }

    private static long write(Path file, long position, long length, InputStream body) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            long written = 0;
            while (written < length) {
                int n = body.read(buffer, 0, (int) Math.min(buffer.length, length - written));
                if (n < 0) {
                    break;
                }
                ByteBuffer bb = ByteBuffer.wrap(buffer, 0, n);
                while (bb.hasRemaining()) {
                    channel.write(bb, position + written + (n - bb.remaining()));
                }
                written += n;
            }
            if (written == length && body.read() >= 0) {
                throw ApiException.badRequest("The request body is longer than Content-Range declares.");
            }
            return written;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UPLOAD_INTERRUPTED", "The chunk could not be read completely.");
        }
    }

    public Upload complete(String userId, String uploadId) {
        synchronized (locks.computeIfAbsent(uploadId, k -> new Object())) {
            Upload upload = get(userId, uploadId);
            if (upload.getStatus() != UploadStatus.UPLOADING) {
                throw ApiException.conflict("UPLOAD_NOT_ACTIVE", "The upload is " + upload.getStatus() + ".");
            }
            if (upload.getReceived() != upload.getSize()) {
                throw ApiException.conflict("UPLOAD_INCOMPLETE",
                        "Received " + upload.getReceived() + " of " + upload.getSize() + " bytes.");
            }
            Library library = libraries.findById(upload.getLibraryId())
                    .orElseThrow(() -> ApiException.conflict("LIBRARY_NOT_FOUND", "The target library no longer exists."));
            Path target;
            try {
                target = uniqueTarget(Path.of(library.getPaths().get(0)), upload.getFilename());
                Files.move(tempFile(upload), target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                target = null;
            } catch (IOException e) {
                throw new IllegalStateException("Cannot move upload into the library", e);
            }
            if (target == null) {
                try {
                    target = uniqueTarget(Path.of(library.getPaths().get(0)), upload.getFilename());
                    Files.move(tempFile(upload), target);
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot move upload into the library", e);
                }
            }
            upload.setStatus(UploadStatus.PROCESSING);
            upload.setUpdatedAt(Instant.now());
            uploads.save(upload);
            Path file = target;
            Job job = jobs.submit("UPLOAD_PROCESSING", userId, upload.getId(), handle -> process(upload.getId(), library, file));
            upload.setJobId(job.getId());
            return uploads.save(upload);
        }
    }

    private void process(String uploadId, Library library, Path file) {
        Upload upload = uploads.findById(uploadId).orElseThrow();
        try {
            String mediaId = scanner.addOrUpdate(library, file);
            upload.setMediaId(mediaId);
            upload.setStatus(UploadStatus.COMPLETED);
            events.publishForUser(EventType.UPLOAD_COMPLETED, upload.getUserId(), Map.of("uploadId", uploadId,
                    "mediaId", mediaId));
        } catch (RuntimeException e) {
            log.warn("Processing upload {} failed", uploadId, e);
            upload.setStatus(UploadStatus.FAILED);
            upload.setError(e.getMessage());
            events.publishForUser(EventType.UPLOAD_FAILED, upload.getUserId(), Map.of("uploadId", uploadId));
            throw e;
        } finally {
            upload.setUpdatedAt(Instant.now());
            uploads.save(upload);
        }
    }

    public void cancel(String userId, String uploadId) {
        synchronized (locks.computeIfAbsent(uploadId, k -> new Object())) {
            Upload upload = get(userId, uploadId);
            if (upload.getStatus() != UploadStatus.UPLOADING) {
                throw ApiException.conflict("UPLOAD_NOT_ACTIVE", "Only uploads in progress can be cancelled.");
            }
            deleteTemp(upload);
            upload.setStatus(UploadStatus.CANCELLED);
            upload.setUpdatedAt(Instant.now());
            uploads.save(upload);
        }
        locks.remove(uploadId);
    }

    /** Abandoned uploads are cancelled and their partial files removed. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    void expireStale() {
        Instant cutoff = Instant.now().minus(properties.getUploads().getStaleAfter());
        for (Upload upload : uploads.findByStatusAndUpdatedAtBefore(UploadStatus.UPLOADING, cutoff)) {
            deleteTemp(upload);
            upload.setStatus(UploadStatus.CANCELLED);
            upload.setError("Expired");
            uploads.save(upload);
            events.publishForUser(EventType.UPLOAD_FAILED, upload.getUserId(), Map.of("uploadId", upload.getId()));
        }
    }

    private void deleteTemp(Upload upload) {
        try {
            Files.deleteIfExists(tempFile(upload));
        } catch (IOException e) {
            log.warn("Could not delete partial upload {}", upload.getId(), e);
        }
    }

    private Path tempDir() {
        return properties.getDataDir().resolve("uploads");
    }

    private Path tempFile(Upload upload) {
        return tempDir().resolve(upload.getId() + ".part");
    }

    private static Path uniqueTarget(Path dir, String filename) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve(filename);
        String base = filename.substring(0, filename.lastIndexOf('.'));
        String ext = filename.substring(filename.lastIndexOf('.'));
        for (int i = 1; Files.exists(target); i++) {
            target = dir.resolve(base + " (" + i + ")" + ext);
        }
        if (!target.normalize().startsWith(dir.normalize())) {
            throw new IOException("Invalid target path");
        }
        return target;
    }

    /** Keeps only the last path segment and removes characters that are unsafe in file names. */
    static String sanitize(String filename) {
        if (filename == null) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "filename is required");
        }
        String name = filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\x00-\\x1f<>:\"|?*]", "_").trim();
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        if (name.isEmpty() || name.length() > 255 || name.lastIndexOf('.') <= 0) {
            throw ApiException.unprocessable("INVALID_FILENAME", "The filename is not valid.");
        }
        return name;
    }

    private static String extension(String name) {
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
