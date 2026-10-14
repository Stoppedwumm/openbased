package org.openbased.sync;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * A change that alters which media a user can see without touching the media itself: a library's access changed
 * or it was deleted ({@code userId} null: affects everyone), or a user's roles or permissions changed. Clients
 * that synced before it must sync everything again.
 */
@Entity
@Table(name = "sync_resets", indexes = @Index(columnList = "createdAt"))
public class SyncReset {

    @Id
    private String id;

    /** The user affected, or null for a change that may affect every user. */
    private String userId;

    @Column(nullable = false, length = 200)
    private String reason;

    @Column(nullable = false)
    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
