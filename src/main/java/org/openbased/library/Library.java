package org.openbased.library;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "libraries")
public class Library {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LibraryType type;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "library_paths", joinColumns = @JoinColumn(name = "library_id"))
    @OrderColumn(name = "position")
    @Column(name = "path", length = 4096)
    private List<String> paths = new ArrayList<>();

    /** When true every user can see the library; otherwise only {@link #memberIds} and administrators. */
    private boolean allUsers = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "library_members", joinColumns = @JoinColumn(name = "library_id"))
    @Column(name = "user_id")
    private Set<String> memberIds = new HashSet<>();

    private Instant createdAt;
    private Instant lastScannedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LibraryType getType() { return type; }
    public void setType(LibraryType type) { this.type = type; }
    public List<String> getPaths() { return paths; }
    public void setPaths(List<String> paths) { this.paths = paths; }
    public boolean isAllUsers() { return allUsers; }
    public void setAllUsers(boolean allUsers) { this.allUsers = allUsers; }
    public Set<String> getMemberIds() { return memberIds; }
    public void setMemberIds(Set<String> memberIds) { this.memberIds = memberIds; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getLastScannedAt() { return lastScannedAt; }
    public void setLastScannedAt(Instant lastScannedAt) { this.lastScannedAt = lastScannedAt; }
}
