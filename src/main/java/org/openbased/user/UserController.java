package org.openbased.user;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.openbased.common.ApiException;
import org.openbased.common.PageResponse;
import org.openbased.common.Paging;
import org.openbased.security.AccessService;
import org.openbased.security.Scopes;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Users")
public class UserController {

    private final UserRepository users;
    private final UserService userService;
    private final AccessService access;

    public UserController(UserRepository users, UserService userService, AccessService access) {
        this.users = users;
        this.userService = userService;
        this.access = access;
    }

    public record UserResponse(String id, String username, String displayName, String email, Set<Role> roles,
            boolean enabled, Instant createdAt) {
        static UserResponse of(User u) {
            return new UserResponse(u.getId(), u.getUsername(), u.getDisplayName(), u.getEmail(), u.getRoles(),
                    u.isEnabled(), u.getCreatedAt());
        }
    }

    public record CurrentUser(String id, String username, String displayName, List<String> roles) {
    }

    public record Permissions(Set<String> permissions) {
    }

    public record CreateUserRequest(
            @NotBlank @Size(min = 2, max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String username,
            @NotBlank @Size(min = 8, max = 200) String password,
            @Size(max = 200) String displayName,
            @Email String email,
            Set<Role> roles,
            Set<String> permissions) {
    }

    public record UpdateUserRequest(@Size(min = 8, max = 200) String password, @Size(max = 200) String displayName,
            @Email String email, Set<Role> roles, Set<String> permissions, Boolean enabled) {
    }

    @GetMapping("/userinfo")
    @Operation(summary = "OpenID Connect UserInfo")
    public Map<String, Object> userinfo() {
        access.require(Scopes.OPENID);
        User user = access.user();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", user.getId());
        if (access.principal().scopes().contains(Scopes.PROFILE)) {
            claims.put("name", user.getDisplayName());
            claims.put("preferred_username", user.getUsername());
        }
        if (access.principal().scopes().contains(Scopes.EMAIL) && user.getEmail() != null) {
            claims.put("email", user.getEmail());
        }
        return claims;
    }

    @GetMapping("/users/me")
    @Operation(summary = "Get the current user")
    public CurrentUser me() {
        access.require(Scopes.PROFILE);
        User user = access.user();
        return new CurrentUser(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getRoles().stream().map(Enum::name).sorted().toList());
    }

    @GetMapping("/users/me/permissions")
    @Operation(summary = "Get the current user's permissions")
    public Permissions myPermissions() {
        access.require(Scopes.PROFILE);
        return new Permissions(access.user().permissions());
    }

    @GetMapping("/users")
    @Operation(summary = "List users")
    public PageResponse<UserResponse> list(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        access.require(Scopes.USERS_READ);
        return PageResponse.of(users.findAll(Paging.of(page, pageSize, Sort.by("username"))), UserResponse::of);
    }

    @GetMapping("/users/{userId}")
    @Operation(summary = "Get a user")
    public UserResponse get(@PathVariable String userId) {
        access.require(Scopes.USERS_READ);
        return users.findById(userId).map(UserResponse::of)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "The requested user does not exist."));
    }

    @PostMapping("/users")
    @Operation(summary = "Create a user")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        access.require(Scopes.USERS_WRITE);
        guardEscalation(request.roles(), request.permissions());
        User user = userService.create(request.username(), request.password(), request.displayName(),
                request.email(), request.roles(), request.permissions());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.of(user));
    }

    @PatchMapping("/users/{userId}")
    @Operation(summary = "Update a user")
    public UserResponse update(@PathVariable String userId, @Valid @RequestBody UpdateUserRequest request) {
        access.require(Scopes.USERS_WRITE);
        guardEscalation(request.roles(), request.permissions());
        User target = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "The requested user does not exist."));
        if (!access.has(Scopes.SERVER_ADMIN) && !target.permissions().stream().allMatch(access::has)) {
            throw ApiException.forbidden("PERMISSION_DENIED", "You cannot modify a user with more permissions than you.");
        }
        return UserResponse.of(userService.update(userId, request.password(), request.displayName(), request.email(),
                request.roles(), request.permissions(), request.enabled()));
    }

    /** Only callers holding server.admin may hand out permissions they do not hold themselves. */
    private void guardEscalation(Set<Role> roles, Set<String> permissions) {
        if (access.has(Scopes.SERVER_ADMIN)) {
            return;
        }
        if (roles != null && roles.contains(Role.ADMIN)) {
            throw ApiException.forbidden("PERMISSION_DENIED", "Only administrators can grant the ADMIN role.");
        }
        if (permissions != null) {
            for (String p : permissions) {
                if (!access.has(p)) {
                    throw ApiException.forbidden("PERMISSION_DENIED", "You cannot grant the permission " + p + ".");
                }
            }
        }
    }
}
