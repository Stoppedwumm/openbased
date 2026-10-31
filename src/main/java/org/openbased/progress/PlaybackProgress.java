package org.openbased.progress;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** A user's resume position for a media item. Positions and durations are in seconds. */
@Entity
@Table(name = "playback_progress",
        uniqueConstraints = @UniqueConstraint(columnNames = { "userId", "mediaId" }),
        indexes = @Index(columnList = "userId, updatedAt"))
public class PlaybackProgress {

    @Id
    private String id;
    private String userId;
    private String mediaId;
    private double position;
    private Double duration;
    private boolean completed;
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getMediaId() { return mediaId; }
    public void setMediaId(String mediaId) { this.mediaId = mediaId; }
    public double getPosition() { return position; }
    public void setPosition(double position) { this.position = position; }
    public Double getDuration() { return duration; }
    public void setDuration(Double duration) { this.duration = duration; }
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
