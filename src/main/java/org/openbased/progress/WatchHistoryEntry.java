package org.openbased.progress;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** One viewing of a media item. Progress updates within a few hours extend the same viewing. */
@Entity
@Table(name = "watch_history", indexes = { @Index(columnList = "userId, watchedAt"), @Index(columnList = "mediaId") })
public class WatchHistoryEntry {

    @Id
    private String id;
    private String userId;
    private String mediaId;
    private Instant watchedAt;
    private boolean completed;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getMediaId() { return mediaId; }
    public void setMediaId(String mediaId) { this.mediaId = mediaId; }
    public Instant getWatchedAt() { return watchedAt; }
    public void setWatchedAt(Instant watchedAt) { this.watchedAt = watchedAt; }
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }
}
