package org.openbased.token;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.openbased.common.ApiException;
import org.openbased.config.OpenBasedProperties;
import org.openbased.security.AccessService;
import org.openbased.security.Scopes;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Device linking for TVs and media players. {@code POST /api/v1/device-links} and
 * {@code POST /api/v1/device-links/token} are called by the device without credentials; viewing,
 * approving and denying a code requires a signed-in user.
 */
@RestController
@RequestMapping("/api/v1/device-links")
@Tag(name = "Device linking")
public class DeviceLinkController {

    private final DeviceLinkService links;
    private final AccessService access;
    private final OpenBasedProperties properties;

    public DeviceLinkController(DeviceLinkService links, AccessService access, OpenBasedProperties properties) {
        this.links = links;
        this.access = access;
        this.properties = properties;
    }

    public record StartRequest(@Size(max = 100) String name) {
    }

    public record StartResponse(String deviceCode, String userCode, String verificationUri,
            String verificationUriComplete, long expiresIn, int interval, List<String> scopes) {
    }

    public record PollRequest(@NotBlank String deviceCode) {
    }

    /** @param user display name of the account the device was linked to */
    public record PollResponse(DeviceLinkService.Status status, String token, Instant expiresAt, String user) {
    }

    public record PendingLink(String userCode, String name, Instant createdAt, List<String> scopes) {
    }

    @PostMapping
    @Operation(summary = "Start linking a device (no authentication). Show userCode to the user")
    public ResponseEntity<StartResponse> start(@Valid @RequestBody(required = false) StartRequest request) {
        DeviceLinkService.Link link = links.start(request == null ? null : request.name());
        String code = DeviceLinkService.display(link.userCode());
        String uri = properties.getIssuer() + "/#/link";
        return ResponseEntity.status(HttpStatus.CREATED).body(new StartResponse(link.deviceCode(), code, uri,
                uri + "/" + code, DeviceLinkService.LIFETIME.toSeconds(), DeviceLinkService.POLL_INTERVAL_SECONDS,
                DeviceLinkService.DEVICE_SCOPES));
    }

    @PostMapping("/token")
    @Operation(summary = "Poll for the device token (no authentication). Returns PENDING until approved")
    public PollResponse poll(@Valid @RequestBody PollRequest request) {
        DeviceLinkService.Link link = links.poll(request.deviceCode());
        return switch (link.status()) {
            case PENDING -> new PollResponse(DeviceLinkService.Status.PENDING, null, null, null);
            case APPROVED -> new PollResponse(DeviceLinkService.Status.APPROVED, link.token(),
                    link.tokenExpiresAt(), link.userName());
            case DENIED -> throw ApiException.forbidden("DEVICE_LINK_DENIED", "Linking was declined.");
        };
    }

    @GetMapping("/{userCode}")
    @Operation(summary = "Show the device waiting for a code")
    public PendingLink get(@PathVariable String userCode) {
        access.require(Scopes.PROFILE);
        access.user();
        DeviceLinkService.Link link = links.pending(userCode);
        return new PendingLink(DeviceLinkService.display(link.userCode()), link.name(), link.createdAt(),
                DeviceLinkService.DEVICE_SCOPES);
    }

    @PostMapping("/{userCode}/approve")
    @Operation(summary = "Approve a device; it receives a personal access token for the current user")
    public PendingLink approve(@PathVariable String userCode) {
        access.require(Scopes.PROFILE);
        DeviceLinkService.Link link = links.approve(userCode, access.user(), access.principal());
        return new PendingLink(DeviceLinkService.display(link.userCode()), link.name(), link.createdAt(),
                DeviceLinkService.DEVICE_SCOPES);
    }

    @PostMapping("/{userCode}/deny")
    @Operation(summary = "Decline a device")
    public ResponseEntity<Void> deny(@PathVariable String userCode) {
        access.require(Scopes.PROFILE);
        access.user();
        links.deny(userCode);
        return ResponseEntity.noContent().build();
    }
}
