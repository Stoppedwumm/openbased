package org.openbased.upload;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "uploads")
public class Upload {

    @Id
    private String id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String libraryId;

    @Column(nullable = false)
    private String filename;

    private long size;
    private long received;
    private long chunkSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UploadStatus status;

    private String mediaId;
    private String jobId;

    @Column(length = 2000)
    private String error;

    private Instant createdAt;
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getLibraryId() { return libraryId; }
    public void setLibraryId(String libraryId) { this.libraryId = libraryId; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    public long getReceived() { return received; }
    public void setReceived(long received) { this.received = received; }
    public long getChunkSize() { return chunkSize; }
    public void setChunkSize(long chunkSize) { this.chunkSize = chunkSize; }
    public UploadStatus getStatus() { return status; }
    public void setStatus(UploadStatus status) { this.status = status; }
    public String getMediaId() { return mediaId; }
    public void setMediaId(String mediaId) { this.mediaId = mediaId; }
    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
