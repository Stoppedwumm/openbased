package org.openbased.download;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;

import org.openbased.common.ApiException;
import org.openbased.common.Ids;
import org.openbased.config.OpenBasedProperties;
import org.openbased.event.EventBus;
import org.openbased.event.EventType;
import org.openbased.job.JobService;
import org.openbased.media.MediaFile;
import org.openbased.media.MediaItem;
import org.openbased.media.MediaLookup;
import org.openbased.playback.PlaybackMode;
import org.openbased.playback.PlaybackPlan;
import org.openbased.playback.Transcoder;
import org.openbased.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Prepares files for keeping on a device. The server decides like it does for playback: when the client can play
 * the original (and it is within the requested limits) the original is served; otherwise a job remuxes or
 * transcodes it into the data directory, where it is kept for {@code openbased.downloads.retention}.
 */
@Service
public class DownloadService {

    static final String JOB_TYPE = "download";

    private static final Logger log = LoggerFactory.getLogger(DownloadService.class);

    private final DownloadRepository downloads;
    private final MediaLookup lookup;
    private final Transcoder transcoder;
    private final JobService jobs;
    private final EventBus events;
    private final OpenBasedProperties properties;

    public DownloadService(DownloadRepository downloads, MediaLookup lookup, Transcoder transcoder, JobService jobs,
            EventBus events, OpenBasedProperties properties) {
        this.downloads = downloads;
        this.lookup = lookup;
        this.transcoder = transcoder;
        this.jobs = jobs;
        this.events = events;
        this.properties = properties;
    }

    /** A new download, or an existing pending or ready one of the same file prepared the same way. */
    public record Created(Download download, boolean existing) {
    }

    /** Not transactional: the download must be committed before its job can pick it up. */
    public synchronized Created create(User user, String mediaId, String fileId, PlaybackPlan.Capabilities caps,
            Transcoder.Limits limits) {
        MediaItem item = lookup.media(mediaId);
        MediaFile file = lookup.file(item, fileId);
        PlaybackPlan plan = plan(file, caps, limits);
        // Limits only matter for what is re-encoded; keep them off the record otherwise so equal requests match.
        Transcoder.Limits applied = plan.mode() == PlaybackMode.TRANSCODE && plan.videoCodec() != null
                ? limits : Transcoder.Limits.NONE;

        Optional<Download> same = downloads.findByUserIdAndFileIdAndStatusIn(user.getId(), file.getId(),
                EnumSet.of(DownloadStatus.PENDING, DownloadStatus.READY)).stream()
                .filter(d -> d.getMode() == plan.mode() && Objects.equals(d.getContainer(), plan.container())
                        && Objects.equals(d.getVideoCodec(), plan.videoCodec())
                        && Objects.equals(d.getAudioCodec(), plan.audioCodec())
                        && Objects.equals(d.getMaxHeight(), applied.maxHeight())
                        && Objects.equals(d.getMaxVideoBitrate(), applied.maxVideoBitrate()))
                .findFirst();
        if (same.isPresent()) {
            return new Created(same.get(), true);
        }

        Download d = new Download();
        d.setId(Ids.prefixed("dl"));
        d.setUserId(user.getId());
        d.setMediaId(item.getId());
        d.setFileId(file.getId());
        d.setMode(plan.mode());
        d.setContainer(plan.container());
        d.setVideoCodec(plan.videoCodec());
        d.setAudioCodec(plan.audioCodec());
        d.setMaxHeight(applied.maxHeight());
        d.setMaxVideoBitrate(applied.maxVideoBitrate());
        d.setCreatedAt(Instant.now());
        if (plan.mode() == PlaybackMode.DIRECT_PLAY) {
            d.setStatus(DownloadStatus.READY);
            d.setSize(file.getSize());
            d.setReadyAt(d.getCreatedAt());
            return new Created(downloads.save(d), false);
        }
        int max = properties.getDownloads().getMaxPendingPerUser();
        if (downloads.countByUserIdAndStatus(user.getId(), DownloadStatus.PENDING) >= max) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_DOWNLOADS",
                    "At most " + max + " downloads can be prepared at once. Wait for one to finish or cancel one.");
        }
        d.setStatus(DownloadStatus.PENDING);
        downloads.save(d);
        String jobId = jobs.submit(JOB_TYPE, user.getId(), d.getId(),
                handle -> prepare(d.getId(), file, plan, applied, handle)).getId();
        // The job may already have finished; only add the job ID.
        downloads.findById(d.getId()).ifPresent(saved -> {
            saved.setJobId(jobId);
            downloads.save(saved);
        });
        return new Created(downloads.findById(d.getId()).orElse(d), false);
    }

    /**
     * Playback's decision, except that a file over a limit is transcoded even when the client could play it.
     * The bitrate is checked against the whole file's average, as the per-stream bitrate is not known.
     */
    static PlaybackPlan plan(MediaFile file, PlaybackPlan.Capabilities caps, Transcoder.Limits limits) {
        PlaybackPlan plan = PlaybackPlan.decide(file, caps);
        boolean tooTall = limits.maxHeight() != null && file.getHeight() != null
                && file.getHeight() > limits.maxHeight();
        boolean tooLarge = limits.maxVideoBitrate() != null && file.getDuration() != null && file.getDuration() > 0
                && file.getSize() * 8 * 1000 / file.getDuration() > limits.maxVideoBitrate();
        if ((tooTall || tooLarge) && file.getVideoCodec() != null && plan.mode() != PlaybackMode.TRANSCODE) {
            return PlaybackPlan.transcode(file, caps);
        }
        return plan;
    }

    private void prepare(String downloadId, MediaFile file, PlaybackPlan plan, Transcoder.Limits limits,
            JobService.Handle handle) throws Exception {
        Path dir = directory();
        Files.createDirectories(dir);
        Path part = dir.resolve(downloadId + ".part");
        Path target = dir.resolve(downloadId + "." + plan.container());
        try {
            transcoder.toFile(file, plan, limits, part, new Transcoder.Progress() {
                @Override
                public void percent(int percent) {
                    handle.progress(percent);
                }

                @Override
                public boolean cancelled() {
                    return handle.isCancelled();
                }
            });
        } catch (CancellationException e) {
            finish(downloadId, d -> d.setStatus(DownloadStatus.CANCELLED), null);
            return;
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            finish(downloadId, d -> {
                d.setStatus(DownloadStatus.FAILED);
                d.setError(message.length() > 2000 ? message.substring(0, 2000) : message);
            }, EventType.DOWNLOAD_FAILED);
            throw e;
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        long size = Files.size(target);
        boolean kept = finish(downloadId, d -> {
            Instant now = Instant.now();
            d.setStatus(DownloadStatus.READY);
            d.setPath(target.toString());
            d.setSize(size);
            d.setReadyAt(now);
            d.setExpiresAt(now.plus(properties.getDownloads().getRetention()));
        }, EventType.DOWNLOAD_READY);
        if (!kept) {
            // Deleted while it was being prepared.
            Files.deleteIfExists(target);
        }
    }

    /** Applies the change if the download still exists and is pending; returns whether it did. */
    private synchronized boolean finish(String downloadId, java.util.function.Consumer<Download> change,
            EventType event) {
        Optional<Download> found = downloads.findById(downloadId).filter(d -> d.getStatus() == DownloadStatus.PENDING);
        found.ifPresent(d -> {
            change.accept(d);
            downloads.save(d);
            if (event != null) {
                events.publishForUser(event, d.getUserId(), Map.of("downloadId", d.getId(), "mediaId", d.getMediaId()));
            }
        });
        return found.isPresent();
    }

    /** The caller's download; others' downloads are reported as not found. */
    public Download find(User user, String downloadId) {
        return downloads.findById(downloadId).filter(d -> d.getUserId().equals(user.getId()))
                .orElseThrow(() -> ApiException.notFound("DOWNLOAD_NOT_FOUND",
                        "The requested download does not exist."));
    }

    /** Cancels a pending download and deletes its file. */
    public synchronized void delete(User user, String downloadId) {
        Download d = find(user, downloadId);
        if (d.getStatus() == DownloadStatus.PENDING && d.getJobId() != null) {
            try {
                jobs.cancel(d.getJobId());
            } catch (ApiException e) {
                // Already finished.
            }
        }
        deleteFile(d);
        downloads.delete(d);
    }

    public record ServedFile(Path path, String filename, String contentType) {
    }

    public Page<Download> list(User user, Pageable pageable) {
        return downloads.findByUserId(user.getId(), pageable);
    }

    /**
     * The file to serve for a ready download, named after the original with the prepared container's extension.
     * Checks again that the caller can access the media item: library access may have changed, or it was removed.
     */
    public ServedFile file(Download d) {
        MediaFile original = lookup.file(lookup.media(d.getMediaId()), d.getFileId());
        Path originalPath = Path.of(original.getPath());
        return switch (d.getStatus()) {
            case READY -> d.getPath() == null
                    ? new ServedFile(originalPath, originalPath.getFileName().toString(),
                            MediaLookup.contentType(original.getContainer()))
                    : new ServedFile(Path.of(d.getPath()), withExtension(originalPath.getFileName().toString(),
                            d.getContainer()), MediaLookup.contentType(d.getContainer()));
            case PENDING -> throw ApiException.conflict("DOWNLOAD_NOT_READY", "The download is still being prepared.");
            case EXPIRED -> throw new ApiException(HttpStatus.GONE, "DOWNLOAD_EXPIRED",
                    "The prepared file was deleted after the retention period. Request the download again.");
            default -> throw ApiException.conflict("DOWNLOAD_" + d.getStatus().name(),
                    "The download is " + d.getStatus().name().toLowerCase(java.util.Locale.ROOT) + ".");
        };
    }

    static String withExtension(String filename, String extension) {
        int dot = filename.lastIndexOf('.');
        return (dot > 0 ? filename.substring(0, dot) : filename) + "." + extension;
    }

    /** Preparations interrupted by a restart cannot resume; their jobs are failed by {@link JobService} too. */
    @EventListener(ApplicationReadyEvent.class)
    void failInterrupted() {
        for (Download d : downloads.findByStatus(DownloadStatus.PENDING)) {
            d.setStatus(DownloadStatus.FAILED);
            d.setError("Interrupted by server restart");
            downloads.save(d);
            try {
                Files.deleteIfExists(directory().resolve(d.getId() + ".part"));
            } catch (IOException e) {
                log.warn("Could not delete partial download {}", d.getId(), e);
            }
        }
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    void expire() {
        for (Download d : downloads.findByStatusAndExpiresAtBefore(DownloadStatus.READY, Instant.now())) {
            deleteFile(d);
            d.setStatus(DownloadStatus.EXPIRED);
            d.setPath(null);
            downloads.save(d);
        }
    }

    private void deleteFile(Download d) {
        try {
            if (d.getPath() != null) {
                Files.deleteIfExists(Path.of(d.getPath()));
            }
            Files.deleteIfExists(directory().resolve(d.getId() + ".part"));
        } catch (IOException e) {
            log.warn("Could not delete the file of download {}", d.getId(), e);
        }
    }

    private Path directory() {
        return properties.getDataDir().resolve("downloads");
    }
}
