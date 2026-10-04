package org.openbased.media;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** An image stored on disk (local artwork next to media or images downloaded from metadata providers). */
@Entity
@Table(name = "artwork", indexes = @Index(columnList = "mediaId"))
public class Artwork {

    @Id
    private String id;

    @Column(nullable = false)
    private String mediaId;

    /** POSTER or BACKDROP. */
    @Column(nullable = false)
    private String kind;

    @Column(nullable = false, length = 4096)
    private String path;

    private String contentType;

    /** True when the file lives in the data directory and may be deleted with the artwork. */
    private boolean managed;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMediaId() { return mediaId; }
    public void setMediaId(String mediaId) { this.mediaId = mediaId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public boolean isManaged() { return managed; }
    public void setManaged(boolean managed) { this.managed = managed; }
}
