package org.openbased.sync;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** Records that a media item was removed, so clients syncing incrementally can drop it. */
@Entity
@Table(name = "media_tombstones", indexes = @Index(columnList = "removedAt"))
public class MediaTombstone {

    @Id
    private String mediaId;

    @Column(nullable = false)
    private String libraryId;

    @Column(nullable = false)
    private Instant removedAt;

    public String getMediaId() { return mediaId; }
    public void setMediaId(String mediaId) { this.mediaId = mediaId; }
    public String getLibraryId() { return libraryId; }
    public void setLibraryId(String libraryId) { this.libraryId = libraryId; }
    public Instant getRemovedAt() { return removedAt; }
    public void setRemovedAt(Instant removedAt) { this.removedAt = removedAt; }
}
