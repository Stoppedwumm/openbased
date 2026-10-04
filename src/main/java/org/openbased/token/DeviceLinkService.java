package org.openbased.token;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.openbased.common.ApiException;
import org.openbased.common.Ids;
import org.openbased.config.OpenBasedProperties;
import org.openbased.security.ApiPrincipal;
import org.openbased.security.Scopes;
import org.openbased.user.User;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Links devices that have no convenient keyboard (TVs, media players) to a user account, in the
 * style of the OAuth 2.0 device authorization grant (RFC 8628). The device shows a short user code,
 * the user approves it in the web UI, and the device receives a personal access token restricted to
 * {@link #DEVICE_SCOPES}. Pending links are kept in memory and expire after {@link #LIFETIME}.
 */
@Service
public class DeviceLinkService {

    public static final Duration LIFETIME = Duration.ofMinutes(10);
    public static final int POLL_INTERVAL_SECONDS = 5;
    public static final List<String> DEVICE_SCOPES = List.of(
            Scopes.MEDIA_READ, Scopes.MEDIA_STREAM, Scopes.LIBRARY_READ, Scopes.HISTORY_READ, Scopes.HISTORY_WRITE);

    private static final String CODE_ALPHABET = "BCDFGHJKLMNPQRSTVWXZ";
    private static final int MAX_PENDING = 1000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TokenService tokens;
    private final OpenBasedProperties properties;
    private final Map<String, Link> byUserCode = new ConcurrentHashMap<>();
    private final Map<String, Link> byDeviceCode = new ConcurrentHashMap<>();

    public DeviceLinkService(TokenService tokens, OpenBasedProperties properties) {
        this.tokens = tokens;
        this.properties = properties;
    }

    public enum Status {
        PENDING,
        APPROVED,
        DENIED
    }

    public static final class Link {
        private final String userCode;
        private final String deviceCode;
        private final String name;
        private final Instant createdAt = Instant.now();
        private final Instant expiresAt = createdAt.plus(LIFETIME);
        private volatile Status status = Status.PENDING;
        private volatile String token;
        private volatile Instant tokenExpiresAt;
        private volatile String userName;

        Link(String userCode, String deviceCode, String name) {
            this.userCode = userCode;
            this.deviceCode = deviceCode;
            this.name = name;
        }

        public String userCode() { return userCode; }
        public String deviceCode() { return deviceCode; }
        public String name() { return name; }
        public Instant createdAt() { return createdAt; }
        public Instant expiresAt() { return expiresAt; }
        public Status status() { return status; }
        public String token() { return token; }
        public Instant tokenExpiresAt() { return tokenExpiresAt; }
        public String userName() { return userName; }

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    public Link start(String deviceName) {
        expire();
        if (byUserCode.size() >= MAX_PENDING) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TOO_MANY_DEVICE_LINKS",
                    "Too many devices are waiting to be linked. Try again later.");
        }
        String name = deviceName == null || deviceName.isBlank() ? "Device" : deviceName.trim();
        if (name.length() > 100) {
            name = name.substring(0, 100);
        }
        Link link;
        do {
            link = new Link(randomUserCode(), Ids.randomAlphanumeric(48), name);
        } while (byUserCode.putIfAbsent(link.userCode(), link) != null);
        byDeviceCode.put(link.deviceCode(), link);
        return link;
    }

    /** Called by the device. Hands out the token once, after which the link is forgotten. */
    public Link poll(String deviceCode) {
        Link link = deviceCode == null ? null : byDeviceCode.get(deviceCode);
        if (link == null || link.isExpired()) {
            throw ApiException.notFound("DEVICE_LINK_EXPIRED", "The link code has expired. Start again.");
        }
        if (link.status() != Status.PENDING) {
            forget(link);
        }
        return link;
    }

    /** Looks up a pending link by the code the user typed. */
    public Link pending(String userCode) {
        Link link = byUserCode.get(normalize(userCode));
        if (link == null || link.isExpired() || link.status() != Status.PENDING) {
            throw ApiException.notFound("DEVICE_LINK_NOT_FOUND",
                    "No device is waiting for this code. Check the code shown on the device; codes expire after "
                            + LIFETIME.toMinutes() + " minutes.");
        }
        return link;
    }

    /**
     * Approves a link for the user. The device token gets the device scopes the user and the approving
     * credential both hold, and always includes {@code media.read} and {@code media.stream}.
     */
    public synchronized Link approve(String userCode, User user, ApiPrincipal caller) {
        Link link = pending(userCode);
        Set<String> scopes = new LinkedHashSet<>();
        for (String scope : DEVICE_SCOPES) {
            if (user.hasPermission(scope) && caller.scopes().contains(scope)) {
                scopes.add(scope);
            }
        }
        if (!scopes.containsAll(Set.of(Scopes.MEDIA_READ, Scopes.MEDIA_STREAM))) {
            throw ApiException.forbidden("PERMISSION_DENIED", "Linking a device requires the media.read and "
                    + "media.stream permissions.");
        }
        long ttl = properties.getTokens().getPersonalTokenMaxTtl().toSeconds();
        TokenService.Created created = tokens.create(user, caller, link.name(), scopes, ttl);
        link.token = created.plaintext();
        link.tokenExpiresAt = created.token().getExpiresAt();
        link.userName = user.getDisplayName();
        link.status = Status.APPROVED;
        byUserCode.remove(link.userCode());
        return link;
    }

    public synchronized Link deny(String userCode) {
        Link link = pending(userCode);
        link.status = Status.DENIED;
        byUserCode.remove(link.userCode());
        return link;
    }

    @Scheduled(fixedDelay = 60_000)
    void expire() {
        byDeviceCode.values().stream().filter(Link::isExpired).toList().forEach(this::forget);
    }

    private void forget(Link link) {
        byUserCode.remove(link.userCode());
        byDeviceCode.remove(link.deviceCode());
    }

    /** {@code BKMQTRWZ} is displayed as {@code BKMQ-TRWZ}; input ignores case, spaces and dashes. */
    public static String display(String userCode) {
        return userCode.substring(0, 4) + "-" + userCode.substring(4);
    }

    static String normalize(String userCode) {
        return userCode == null ? "" : userCode.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z]", "");
    }

    private static String randomUserCode() {
        StringBuilder code = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }
}
