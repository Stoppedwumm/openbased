package org.openbased.user;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.openbased.common.ApiException;
import org.openbased.common.Ids;
import org.openbased.event.EventBus;
import org.openbased.event.EventType;
import org.openbased.security.Scopes;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final EventBus events;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder, EventBus events) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
    }

    @Transactional
    public User create(String username, String password, String displayName, String email, Set<Role> roles,
            Set<String> extraPermissions) {
        if (users.existsByUsername(username)) {
            throw ApiException.conflict("USERNAME_TAKEN", "The username is already in use.");
        }
        validatePermissions(extraPermissions);
        User user = new User();
        user.setId(Ids.ulid());
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setRoles(new HashSet<>(roles == null || roles.isEmpty() ? Set.of(Role.USER) : roles));
        user.setExtraPermissions(new HashSet<>(extraPermissions == null ? Set.of() : extraPermissions));
        user.setCreatedAt(Instant.now());
        users.save(user);
        events.publish(EventType.USER_CREATED, Map.of("userId", user.getId()));
        return user;
    }

    @Transactional
    public User update(String id, String password, String displayName, String email, Set<Role> roles,
            Set<String> extraPermissions, Boolean enabled) {
        User user = users.findById(id)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "The requested user does not exist."));
        if (password != null) {
            user.setPasswordHash(passwordEncoder.encode(password));
        }
        if (displayName != null) {
            user.setDisplayName(displayName);
        }
        if (email != null) {
            user.setEmail(email);
        }
        if (roles != null) {
            user.setRoles(new HashSet<>(roles));
        }
        if (extraPermissions != null) {
            validatePermissions(extraPermissions);
            user.setExtraPermissions(new HashSet<>(extraPermissions));
        }
        if (enabled != null) {
            user.setEnabled(enabled);
        }
        users.save(user);
        events.publish(EventType.USER_UPDATED, Map.of("userId", user.getId()));
        return user;
    }

    private static void validatePermissions(Set<String> permissions) {
        if (permissions == null) {
            return;
        }
        for (String p : permissions) {
            if (!Scopes.ALL.contains(p)) {
                throw ApiException.unprocessable("UNKNOWN_PERMISSION", "Unknown permission: " + p);
            }
        }
    }
}
