package org.openbased.user;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import org.openbased.security.Scopes;

@Entity
@Table(name = "users")
public class User {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String username;

    private String displayName;
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    private boolean enabled = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private Set<Role> roles = new HashSet<>();

    /** Permissions granted in addition to those of the user's roles. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_permissions", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "permission")
    private Set<String> extraPermissions = new HashSet<>();

    private Instant createdAt;

    /** All permissions held by the user, in canonical order. */
    public Set<String> permissions() {
        Set<String> all = new HashSet<>(extraPermissions);
        roles.forEach(r -> all.addAll(r.permissions()));
        Set<String> ordered = new java.util.LinkedHashSet<>();
        Scopes.ALL.stream().filter(all::contains).forEach(ordered::add);
        new TreeSet<>(all).stream().filter(p -> !ordered.contains(p)).forEach(ordered::add);
        return ordered;
    }

    public boolean hasPermission(String permission) {
        return extraPermissions.contains(permission) || roles.stream().anyMatch(r -> r.permissions().contains(permission));
    }

    public boolean isAdmin() {
        return hasPermission(Scopes.SERVER_ADMIN);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName != null ? displayName : username; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Set<Role> getRoles() { return roles; }
    public void setRoles(Set<Role> roles) { this.roles = roles; }
    public Set<String> getExtraPermissions() { return extraPermissions; }
    public void setExtraPermissions(Set<String> extraPermissions) { this.extraPermissions = extraPermissions; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
