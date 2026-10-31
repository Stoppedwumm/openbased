package org.openbased.download;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import org.openbased.playback.PlaybackMode;

/**
 * A file prepared for a user to keep on a device. With {@link PlaybackMode#DIRECT_PLAY} the original is served
 * and nothing is stored; otherwise a remuxed or transcoded copy is written to the data directory.
 */
@Entity
@Table(name = "downloads", indexes = { @Index(columnList = "userId, createdAt"), @Index(columnList = "status") })
public class Download {

    @Id
    private String id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String mediaId;

    @Column(nullable = false)
    private String fileId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlaybackMode mode;

    private String container;
    private String videoCodec;
    private String audioCodec;
    private Integer maxHeight;
    private Long maxVideoBitrate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DownloadStatus status;

    private String jobId;

    /** The prepared file; null for direct downloads of the original. */
    private String path;

    private Long size;

    @Column(length = 2000)
    private String error;

    private Instant createdAt;
    private Instant readyAt;
    private Instant expiresAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getMediaId() { return mediaId; }
    public void setMediaId(String mediaId) { this.mediaId = mediaId; }
    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public PlaybackMode getMode() { return mode; }
    public void setMode(PlaybackMode mode) { this.mode = mode; }
    public String getContainer() { return container; }
    public void setContainer(String container) { this.container = container; }
    public String getVideoCodec() { return videoCodec; }
    public void setVideoCodec(String videoCodec) { this.videoCodec = videoCodec; }
    public String getAudioCodec() { return audioCodec; }
    public void setAudioCodec(String audioCodec) { this.audioCodec = audioCodec; }
    public Integer getMaxHeight() { return maxHeight; }
    public void setMaxHeight(Integer maxHeight) { this.maxHeight = maxHeight; }
    public Long getMaxVideoBitrate() { return maxVideoBitrate; }
    public void setMaxVideoBitrate(Long maxVideoBitrate) { this.maxVideoBitrate = maxVideoBitrate; }
    public DownloadStatus getStatus() { return status; }
    public void setStatus(DownloadStatus status) { this.status = status; }
    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public Long getSize() { return size; }
    public void setSize(Long size) { this.size = size; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getReadyAt() { return readyAt; }
    public void setReadyAt(Instant readyAt) { this.readyAt = readyAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
