package org.openbased.user;

import java.util.Set;

import org.openbased.security.Scopes;

/** Roles grant a fixed set of permissions; users may hold additional permissions individually. */
public enum Role {

    USER(Set.of(
            Scopes.OPENID, Scopes.PROFILE, Scopes.EMAIL,
            Scopes.MEDIA_READ, Scopes.MEDIA_STREAM,
            Scopes.LIBRARY_READ,
            Scopes.HISTORY_READ, Scopes.HISTORY_WRITE)),

    ADMIN(Scopes.ALL);

    private final Set<String> permissions;

    Role(Set<String> permissions) {
        this.permissions = permissions;
    }

    public Set<String> permissions() {
        return permissions;
    }
}
