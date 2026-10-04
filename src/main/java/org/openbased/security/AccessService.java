package org.openbased.security;

import java.util.Set;
import java.util.stream.Collectors;

import org.openbased.common.ApiException;
import org.openbased.library.Library;
import org.openbased.library.LibraryRepository;
import org.openbased.user.User;
import org.openbased.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Authorization checks shared by all endpoints. A protected operation verifies, in order: the caller is
 * authenticated with a valid, unexpired token (enforced by the security filter chain); the token carries
 * the required scope; the user holds the permission of the same name; and the user may access the
 * resource (library membership).
 */
@Service
public class AccessService {

    private static final String USER_ATTRIBUTE = AccessService.class.getName() + ".user";

    private final UserRepository users;
    private final LibraryRepository libraries;

    public AccessService(UserRepository users, LibraryRepository libraries) {
        this.users = users;
        this.libraries = libraries;
    }

    public ApiPrincipal principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof ApiAuthentication api) {
            return api.getPrincipal();
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required.");
    }

    /** Requires the scope on the token and, for user tokens, the matching user permission. */
    public void require(String scope) {
        ApiPrincipal principal = principal();
        if (!principal.scopes().contains(scope)) {
            throw ApiException.forbidden("INSUFFICIENT_SCOPE", "The access token lacks the required scope: " + scope);
        }
        if (principal.userId() != null && !user().hasPermission(scope)) {
            throw ApiException.forbidden("PERMISSION_DENIED", "You do not have the permission: " + scope);
        }
    }

    /** Requires any one of the given scopes (each checked as in {@link #require(String)}). */
    public void requireAny(String... scopes) {
        for (String scope : scopes) {
            if (has(scope)) {
                return;
            }
        }
        require(scopes[0]);
    }

    public boolean has(String scope) {
        ApiPrincipal principal = principal();
        return principal.scopes().contains(scope) && (principal.userId() == null || user().hasPermission(scope));
    }

    /** The acting user. Fails for client-credentials tokens that are not bound to a service user. */
    public User user() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null && attrs.getAttribute(USER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof User u) {
            return u;
        }
        ApiPrincipal principal = principal();
        if (principal.userId() == null) {
            throw ApiException.forbidden("NO_USER_CONTEXT", "This operation requires a user; the client is not bound to one.");
        }
        User user = users.findById(principal.userId()).filter(User::isEnabled)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "The user no longer exists or is disabled."));
        if (attrs != null) {
            attrs.setAttribute(USER_ATTRIBUTE, user, RequestAttributes.SCOPE_REQUEST);
        }
        return user;
    }

    public static boolean canAccess(User user, Library library) {
        return user.isAdmin() || library.isAllUsers() || library.getMemberIds().contains(user.getId());
    }

    /**
     * Loads a library the current user can access. Inaccessible libraries are reported as missing so
     * their existence is not revealed.
     */
    public Library library(String libraryId) {
        Library library = libraries.findById(libraryId).orElse(null);
        if (library == null || !canAccess(user(), library)) {
            throw ApiException.notFound("LIBRARY_NOT_FOUND", "The requested library does not exist.");
        }
        return library;
    }

    public boolean canAccessLibrary(String libraryId) {
        return libraries.findById(libraryId).map(l -> canAccess(user(), l)).orElse(false);
    }

    public Set<String> accessibleLibraryIds() {
        User user = user();
        return libraries.findAll().stream().filter(l -> canAccess(user, l)).map(Library::getId)
                .collect(Collectors.toSet());
    }
}
