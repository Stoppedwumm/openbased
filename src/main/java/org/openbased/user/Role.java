package org.openbased.user;

import java.util.Set;

import org.openbased.security.Scopes;

/**
 * Roles grant a set of permissions; users may hold additional permissions individually. The permissions of
 * {@code USER} can be changed with {@code openbased.roles.user} (see {@link RolePermissions}); {@code ADMIN}
 * always holds every permission.
 */
public enum Role {

    USER(Set.of(
            Scopes.OPENID, Scopes.PROFILE, Scopes.EMAIL,
            Scopes.MEDIA_READ, Scopes.MEDIA_STREAM, Scopes.MEDIA_DOWNLOAD,
            Scopes.LIBRARY_READ,
            Scopes.HISTORY_READ, Scopes.HISTORY_WRITE)),

    ADMIN(Scopes.ALL);

    private final Set<String> defaults;
    private volatile Set<String> permissions;

    Role(Set<String> permissions) {
        this.defaults = Set.copyOf(permissions);
        this.permissions = this.defaults;
    }

    public Set<String> permissions() {
        return permissions;
    }

    public Set<String> defaultPermissions() {
        return defaults;
    }

    /** Replaces the role's permissions; {@code null} restores the defaults. */
    void configure(Set<String> permissions) {
        this.permissions = permissions == null ? defaults : Set.copyOf(permissions);
    }
}
