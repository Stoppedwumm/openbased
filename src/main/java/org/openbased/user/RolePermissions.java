package org.openbased.user;

import java.util.Set;
import java.util.TreeSet;

import org.openbased.config.OpenBasedProperties;
import org.openbased.security.Scopes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Applies {@code openbased.roles.*} to the built-in roles at startup. Unknown permissions stop the server. */
@Component
public class RolePermissions {

    private static final Logger log = LoggerFactory.getLogger(RolePermissions.class);

    public RolePermissions(OpenBasedProperties properties) {
        Set<String> user = properties.getRoles().getUser();
        if (user != null) {
            Set<String> unknown = new TreeSet<>(user);
            unknown.removeAll(Scopes.ALL);
            if (!unknown.isEmpty()) {
                throw new IllegalStateException("openbased.roles.user contains unknown permissions: " + unknown
                        + ". Known permissions: " + Scopes.ALL);
            }
            log.info("USER role permissions: {}", new TreeSet<>(user));
        }
        Role.USER.configure(user);
    }
}
