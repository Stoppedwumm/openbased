package org.openbased.media;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** A file on disk belonging to a media item. File system paths are never exposed through the API. */
@Entity
@Table(name = "media_files", indexes = { @Index(columnList = "mediaId"), @Index(columnList = "libraryId") })
public class MediaFile {

    @Id
    private String id;

    @Column(nullable = false)
    private String mediaId;

    @Column(nullable = false)
    private String libraryId;

    @Column(nullable = false, unique = true, length = 4096)
    private String path;

    private String container;
    private long size;
    /** Duration in milliseconds, when known. */
    private Long duration;
    private String videoCodec;
    private String audioCodec;
    private Integer width;
    private Integer height;
    private Instant modifiedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMediaId() { return mediaId; }
    public void setMediaId(String mediaId) { this.mediaId = mediaId; }
    public String getLibraryId() { return libraryId; }
    public void setLibraryId(String libraryId) { this.libraryId = libraryId; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getContainer() { return container; }
    public void setContainer(String container) { this.container = container; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    public Long getDuration() { return duration; }
    public void setDuration(Long duration) { this.duration = duration; }
    public String getVideoCodec() { return videoCodec; }
    public void setVideoCodec(String videoCodec) { this.videoCodec = videoCodec; }
    public String getAudioCodec() { return audioCodec; }
    public void setAudioCodec(String audioCodec) { this.audioCodec = audioCodec; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }
    public Instant getModifiedAt() { return modifiedAt; }
    public void setModifiedAt(Instant modifiedAt) { this.modifiedAt = modifiedAt; }
}
