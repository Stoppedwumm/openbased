package org.openbased.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Installation-level configuration, bound from {@code openbased.*}.
 */
@ConfigurationProperties(prefix = "openbased")
public class OpenBasedProperties {

    /** Public base URL of the server, used as the OAuth2/OIDC issuer. */
    private String issuer = "http://localhost:8080";

    /** Directory for keys, uploads, artwork, plugins and the embedded database. */
    private Path dataDir = Path.of("data");

    /** Browser origins allowed to call the API (e.g. a separately hosted web UI). */
    private List<String> corsAllowedOrigins = new ArrayList<>();

    private final Bootstrap bootstrap = new Bootstrap();
    private final Tokens tokens = new Tokens();
    private final List<Client> clients = new ArrayList<>();
    private final RateLimit rateLimit = new RateLimit();
    private final Uploads uploads = new Uploads();
    private final Playback playback = new Playback();
    private final Metadata metadata = new Metadata();
    private final Jobs jobs = new Jobs();

    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer; }
    public Path getDataDir() { return dataDir; }
    public void setDataDir(Path dataDir) { this.dataDir = dataDir; }
    public List<String> getCorsAllowedOrigins() { return corsAllowedOrigins; }
    public void setCorsAllowedOrigins(List<String> v) { this.corsAllowedOrigins = v; }
    public Bootstrap getBootstrap() { return bootstrap; }
    public Tokens getTokens() { return tokens; }
    public List<Client> getClients() { return clients; }
    public RateLimit getRateLimit() { return rateLimit; }
    public Uploads getUploads() { return uploads; }
    public Playback getPlayback() { return playback; }
    public Metadata getMetadata() { return metadata; }
    public Jobs getJobs() { return jobs; }

    public Optional<Client> client(String clientId) {
        return clients.stream().filter(c -> c.getClientId().equals(clientId)).findFirst();
    }

    public static class Bootstrap {
        /** Username of the administrator created when no users exist. */
        private String adminUsername = "admin";
        /** Password of the bootstrap administrator. A random one is generated and logged when empty. */
        private String adminPassword;

        public String getAdminUsername() { return adminUsername; }
        public void setAdminUsername(String adminUsername) { this.adminUsername = adminUsername; }
        public String getAdminPassword() { return adminPassword; }
        public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }
    }

    public static class Tokens {
        private Duration accessTokenTtl = Duration.ofMinutes(15);
        private Duration refreshTokenTtl = Duration.ofDays(30);
        /** Lifetime of personal access tokens created without {@code expiresIn}. */
        private Duration personalTokenDefaultTtl = Duration.ofDays(90);
        private Duration personalTokenMaxTtl = Duration.ofDays(365);

        public Duration getAccessTokenTtl() { return accessTokenTtl; }
        public void setAccessTokenTtl(Duration v) { this.accessTokenTtl = v; }
        public Duration getRefreshTokenTtl() { return refreshTokenTtl; }
        public void setRefreshTokenTtl(Duration v) { this.refreshTokenTtl = v; }
        public Duration getPersonalTokenDefaultTtl() { return personalTokenDefaultTtl; }
        public void setPersonalTokenDefaultTtl(Duration v) { this.personalTokenDefaultTtl = v; }
        public Duration getPersonalTokenMaxTtl() { return personalTokenMaxTtl; }
        public void setPersonalTokenMaxTtl(Duration v) { this.personalTokenMaxTtl = v; }
    }

    /** An OAuth2 client registration. */
    public static class Client {
        private String clientId;
        /** Client secret. Leave empty for public clients, which must use PKCE. */
        private String clientSecret;
        private String name;
        private List<String> redirectUris = new ArrayList<>();
        private List<String> postLogoutRedirectUris = new ArrayList<>();
        /** authorization_code, refresh_token, client_credentials. */
        private Set<String> grantTypes = Set.of("authorization_code", "refresh_token");
        /** Scopes the client may request. Defaults to all standard scopes. */
        private Set<String> scopes;
        private boolean requireConsent = false;
        /** For client_credentials: username of the account whose permissions the client acts with. */
        private String serviceUser;

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public List<String> getRedirectUris() { return redirectUris; }
        public void setRedirectUris(List<String> redirectUris) { this.redirectUris = redirectUris; }
        public List<String> getPostLogoutRedirectUris() { return postLogoutRedirectUris; }
        public void setPostLogoutRedirectUris(List<String> v) { this.postLogoutRedirectUris = v; }
        public Set<String> getGrantTypes() { return grantTypes; }
        public void setGrantTypes(Set<String> grantTypes) { this.grantTypes = grantTypes; }
        public Set<String> getScopes() { return scopes; }
        public void setScopes(Set<String> scopes) { this.scopes = scopes; }
        public boolean isRequireConsent() { return requireConsent; }
        public void setRequireConsent(boolean requireConsent) { this.requireConsent = requireConsent; }
        public String getServiceUser() { return serviceUser; }
        public void setServiceUser(String serviceUser) { this.serviceUser = serviceUser; }

        public boolean isPublicClient() { return clientSecret == null || clientSecret.isBlank(); }
    }

    public static class RateLimit {
        private boolean enabled = true;
        /** Requests allowed per client per window. */
        private int limit = 1000;
        private Duration window = Duration.ofMinutes(1);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getLimit() { return limit; }
        public void setLimit(int limit) { this.limit = limit; }
        public Duration getWindow() { return window; }
        public void setWindow(Duration window) { this.window = window; }
    }

    public static class Uploads {
        private long chunkSize = 10 * 1024 * 1024;
        private long maxSize = 200L * 1024 * 1024 * 1024;
        private Duration staleAfter = Duration.ofDays(2);
        private Set<String> allowedExtensions = Set.of(
                "mkv", "mp4", "m4v", "mov", "avi", "webm", "ts", "m2ts", "wmv",
                "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav");

        public long getChunkSize() { return chunkSize; }
        public void setChunkSize(long chunkSize) { this.chunkSize = chunkSize; }
        public long getMaxSize() { return maxSize; }
        public void setMaxSize(long maxSize) { this.maxSize = maxSize; }
        public Duration getStaleAfter() { return staleAfter; }
        public void setStaleAfter(Duration staleAfter) { this.staleAfter = staleAfter; }
        public Set<String> getAllowedExtensions() { return allowedExtensions; }
        public void setAllowedExtensions(Set<String> v) { this.allowedExtensions = v; }
    }

    public static class Playback {
        private String ffmpeg = "ffmpeg";
        private String ffprobe = "ffprobe";
        /** Playback sessions without stream or progress activity for this long are terminated. */
        private Duration idleTimeout = Duration.ofHours(4);

        public String getFfmpeg() { return ffmpeg; }
        public void setFfmpeg(String ffmpeg) { this.ffmpeg = ffmpeg; }
        public String getFfprobe() { return ffprobe; }
        public void setFfprobe(String ffprobe) { this.ffprobe = ffprobe; }
        public Duration getIdleTimeout() { return idleTimeout; }
        public void setIdleTimeout(Duration idleTimeout) { this.idleTimeout = idleTimeout; }
    }

    public static class Metadata {
        /** Provider used when a request does not name one. */
        private String defaultProvider = "tmdb";
        /** Match newly scanned media against the default provider automatically. */
        private boolean autoMatch = true;
        private final Tmdb tmdb = new Tmdb();

        public String getDefaultProvider() { return defaultProvider; }
        public void setDefaultProvider(String defaultProvider) { this.defaultProvider = defaultProvider; }
        public boolean isAutoMatch() { return autoMatch; }
        public void setAutoMatch(boolean autoMatch) { this.autoMatch = autoMatch; }
        public Tmdb getTmdb() { return tmdb; }

        public static class Tmdb {
            /** TMDB v3 API key or v4 read access token. The provider is disabled when empty. */
            private String apiKey;
            private String language = "en-US";
            private String baseUrl = "https://api.themoviedb.org/3";
            private String imageBaseUrl = "https://image.tmdb.org/t/p";

            public String getApiKey() { return apiKey; }
            public void setApiKey(String apiKey) { this.apiKey = apiKey; }
            public String getLanguage() { return language; }
            public void setLanguage(String language) { this.language = language; }
            public String getBaseUrl() { return baseUrl; }
            public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
            public String getImageBaseUrl() { return imageBaseUrl; }
            public void setImageBaseUrl(String imageBaseUrl) { this.imageBaseUrl = imageBaseUrl; }
        }
    }

    public static class Jobs {
        private int workers = 2;

        public int getWorkers() { return workers; }
        public void setWorkers(int workers) { this.workers = workers; }
    }
}
