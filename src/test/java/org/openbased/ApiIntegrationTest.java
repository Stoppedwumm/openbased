package org.openbased;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIntegrationTest {

    private static final Path TMP;

    static {
        try {
            TMP = Files.createTempDirectory("openbased-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("openbased.data-dir", () -> TMP.resolve("data").toString());
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:openbased-test;DB_CLOSE_DELAY=-1");
        registry.add("openbased.bootstrap.admin-password", () -> "adminpass123");
        registry.add("openbased.playback.ffprobe", () -> "/nonexistent/ffprobe");
        registry.add("openbased.metadata.auto-match", () -> "false");
        registry.add("openbased.rate-limit.limit", () -> "100000");
        registry.add("openbased.uploads.chunk-size", () -> "1024");
        registry.add("openbased.clients[0].client-id", () -> "admin-svc");
        registry.add("openbased.clients[0].client-secret", () -> "secret");
        registry.add("openbased.clients[0].grant-types", () -> "client_credentials");
        registry.add("openbased.clients[0].service-user", () -> "admin");
        registry.add("openbased.clients[1].client-id", () -> "bob-svc");
        registry.add("openbased.clients[1].client-secret", () -> "secret");
        registry.add("openbased.clients[1].grant-types", () -> "client_credentials");
        registry.add("openbased.clients[1].service-user", () -> "bob");
    }

    private static final String ALL_SCOPES = String.join(" ", org.openbased.security.Scopes.ALL);

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    @Autowired
    org.openbased.media.MediaItemRepository mediaItems;

    @Autowired
    org.openbased.security.oauth.AuthorizationRepository authorizationRepository;

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private String admin;

    record Response(int status, Map<String, List<String>> headers, String body, JsonNode json) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().get(0)).findFirst().orElse(null);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        admin = token("admin-svc", ALL_SCOPES);
        Response bob = call("GET", "/api/v1/users?pageSize=200", admin, null);
        boolean exists = false;
        for (JsonNode u : bob.json().path("items")) {
            exists |= "bob".equals(u.path("username").asText());
        }
        if (!exists) {
            assertThat(call("POST", "/api/v1/users", admin,
                    Map.of("username", "bob", "password", "bobpassword1")).status()).isEqualTo(201);
        }
    }

    @Test
    void discoveryAndStandardErrors() throws Exception {
        Response discovery = call("GET", "/.well-known/openid-configuration", null, null);
        assertThat(discovery.json().path("userinfo_endpoint").asText()).endsWith("/api/v1/userinfo");
        assertThat(discovery.json().path("jwks_uri").asText()).endsWith("/oauth2/jwks");

        Response unauthenticated = call("GET", "/api/v1/media", null, null);
        assertThat(unauthenticated.status()).isEqualTo(401);
        assertThat(unauthenticated.json().path("error").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(unauthenticated.json().path("requestId").asText()).isNotBlank();
        assertThat(unauthenticated.json().path("timestamp").asText()).isNotBlank();
        assertThat(unauthenticated.header("X-RateLimit-Limit")).isEqualTo("100000");

        Response missing = call("GET", "/api/v1/media/media_missing", admin, null);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.json().path("error").asText()).isEqualTo("MEDIA_NOT_FOUND");

        Response userinfo = call("GET", "/api/v1/userinfo", admin, null);
        assertThat(userinfo.json().path("preferred_username").asText()).isEqualTo("admin");

        Response openapi = call("GET", "/api/v1/openapi.json", null, null);
        assertThat(openapi.status()).isEqualTo(200);
        assertThat(openapi.json().path("paths").has("/api/v1/media/{mediaId}/stream")).isTrue();
    }

    @Test
    void webUiIsServedWithoutAuthentication() throws Exception {
        for (String path : List.of("/", "/callback?code=x&state=y")) {
            HttpResponse<String> page = http.send(HttpRequest.newBuilder(uri(path)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(page.statusCode()).as(path).isEqualTo(200);
            assertThat(page.body()).contains("/app/app.js");
        }
        assertThat(http.send(HttpRequest.newBuilder(uri("/app/app.js")).build(), HttpResponse.BodyHandlers.ofString())
                .statusCode()).isEqualTo(200);
    }

    @Test
    void scanListAndStreamWithRange() throws Exception {
        Path dir = mediaDir("movies-" + System.nanoTime(), "Interstellar (2014).mkv", 5000);
        String libraryId = createLibrary("Movies", dir, true);
        scan(libraryId);

        Response list = call("GET", "/api/v1/media?library=" + libraryId + "&page=0&pageSize=50", admin, null);
        assertThat(list.json().path("total").asInt()).isEqualTo(1);
        JsonNode item = list.json().path("items").get(0);
        assertThat(item.path("title").asText()).isEqualTo("Interstellar");
        assertThat(item.path("year").asInt()).isEqualTo(2014);
        String mediaId = item.path("id").asText();

        Response detail = call("GET", "/api/v1/media/" + mediaId, admin, null);
        assertThat(detail.json().path("files").get(0).path("container").asText()).isEqualTo("mkv");
        assertThat(detail.json().path("files").get(0).has("path")).isFalse();

        HttpResponse<byte[]> range = http.send(HttpRequest.newBuilder(uri("/api/v1/media/" + mediaId + "/stream"))
                .header("Authorization", "Bearer " + admin).header("Range", "bytes=0-99").build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(range.statusCode()).isEqualTo(206);
        assertThat(range.headers().firstValue("Content-Range")).hasValue("bytes 0-99/5000");
        assertThat(range.body()).hasSize(100);

        Response search = call("GET", "/api/v1/search?q=interst", admin, null);
        assertThat(search.json().path("results").findValuesAsText("id")).contains(mediaId);

        Response progress = call("PUT", "/api/v1/media/" + mediaId + "/progress", admin,
                Map.of("position", 1532, "duration", 7200, "completed", false));
        assertThat(progress.json().path("position").asDouble()).isEqualTo(1532);
        JsonNode listed = call("GET", "/api/v1/media?library=" + libraryId, admin, null).json().path("items").get(0);
        assertThat(listed.path("progress").path("position").asDouble()).isEqualTo(1532);
        assertThat(listed.path("progress").path("completed").asBoolean()).isFalse();
        assertThat(call("GET", "/api/v1/media/" + mediaId, admin, null).json().path("progress").path("duration")
                .asDouble()).isEqualTo(7200);
        // Tokens without history.read do not see progress.
        assertThat(call("GET", "/api/v1/media/" + mediaId, token("admin-svc", "media.read"), null).json()
                .path("progress").isNull()).isTrue();
        Response cont = call("GET", "/api/v1/continue-watching", admin, null);
        assertThat(cont.json().path("items").findValuesAsText("mediaId")).contains(mediaId);

        Response session = call("POST", "/api/v1/playback/sessions", admin, Map.of("mediaId", mediaId,
                "capabilities", Map.of("containers", List.of("mkv"))));
        assertThat(session.status()).isEqualTo(201);
        assertThat(session.json().path("mode").asText()).isEqualTo("DIRECT_PLAY");
        String streamUrl = session.json().path("streamUrl").asText();
        assertThat(call("GET", streamUrl, admin, null).status()).isEqualTo(200);
        // Another user cannot use the session.
        assertThat(call("GET", streamUrl, token("bob-svc", "media.stream"), null).status()).isEqualTo(404);
    }

    @Test
    void offlineProgressIsSyncedNewestFirst() throws Exception {
        Path dir = mediaDir("offline-" + System.nanoTime(), "Arrival (2016).mkv", 300);
        Files.write(dir.resolve("Dune (2021).mkv"), new byte[300]);
        String libraryId = createLibrary("Offline", dir, true);
        scan(libraryId);
        JsonNode items = call("GET", "/api/v1/media?library=" + libraryId + "&sort=title", admin, null).json()
                .path("items");
        String arrival = items.get(0).path("id").asText();
        String dune = items.get(1).path("id").asText();
        Instant now = Instant.now();
        // Pretend the items were added a week ago, so progress recorded "yesterday" is plausible.
        Instant added = now.minus(Duration.ofDays(7)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        for (String id : List.of(arrival, dune)) {
            org.openbased.media.MediaItem item = mediaItems.findById(id).orElseThrow();
            item.setAddedAt(added);
            mediaItems.save(item);
        }

        Response current = call("PUT", "/api/v1/media/" + arrival + "/progress", admin,
                Map.of("position", 100, "duration", 7200));
        assertThat(current.json().path("applied").asBoolean()).isTrue();

        // Progress recorded offline yesterday does not replace newer progress, but the viewing is in the history.
        Instant yesterday = now.minus(Duration.ofDays(1)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Response stale = call("PUT", "/api/v1/media/" + arrival + "/progress", admin,
                Map.of("position", 50, "duration", 7200, "recordedAt", yesterday.toString()));
        assertThat(stale.status()).isEqualTo(200);
        assertThat(stale.json().path("applied").asBoolean()).isFalse();
        assertThat(stale.json().path("position").asDouble()).isEqualTo(100);
        assertThat(call("GET", "/api/v1/media/" + arrival + "/progress", admin, null).json().path("position")
                .asDouble()).isEqualTo(100);
        List<Instant> watched = new ArrayList<>();
        for (JsonNode h : call("GET", "/api/v1/history?pageSize=200", admin, null).json().path("items")) {
            if (arrival.equals(h.path("mediaId").asText())) {
                watched.add(Instant.parse(h.path("watchedAt").asText()));
            }
        }
        assertThat(watched).hasSize(2).contains(yesterday);

        // A time before the item was added (e.g. a reset device clock) is clamped to when it was added.
        Response reset = call("PUT", "/api/v1/media/" + arrival + "/progress", admin,
                Map.of("position", 10, "recordedAt", "1970-01-01T00:00:00Z"));
        assertThat(reset.json().path("applied").asBoolean()).isFalse();
        assertThat(call("GET", "/api/v1/history?pageSize=200", admin, null).json().path("items")
                .findValuesAsText("watchedAt")).doesNotContain("1970-01-01T00:00:00Z").contains(added.toString());

        // A time ahead of the server's clock is clamped to it.
        Response future = call("PUT", "/api/v1/media/" + arrival + "/progress", admin,
                Map.of("position", 200, "recordedAt", now.plus(Duration.ofDays(1)).toString()));
        assertThat(future.json().path("applied").asBoolean()).isTrue();
        assertThat(Instant.parse(future.json().path("updatedAt").asText())).isBefore(now.plus(Duration.ofMinutes(5)));

        // Batch updates are applied oldest first and fail one by one.
        Response batch = call("POST", "/api/v1/progress/batch", admin, Map.of("updates", List.of(
                Map.of("mediaId", dune, "position", 400, "recordedAt", now.minus(Duration.ofHours(1)).toString()),
                Map.of("mediaId", dune, "position", 350, "recordedAt", now.minus(Duration.ofHours(2)).toString()),
                Map.of("mediaId", dune, "position", 900, "duration", 600),
                Map.of("mediaId", "media_missing", "position", 1))));
        assertThat(batch.status()).isEqualTo(200);
        JsonNode results = batch.json().path("results");
        assertThat(results.findValuesAsText("status")).containsExactly("APPLIED", "APPLIED", "FAILED", "FAILED");
        assertThat(results.get(2).path("error").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(results.get(3).path("error").asText()).isEqualTo("MEDIA_NOT_FOUND");
        assertThat(call("GET", "/api/v1/media/" + dune + "/progress", admin, null).json().path("position")
                .asDouble()).isEqualTo(400);

        // A viewing finished offline is not merged into a later, unfinished viewing on another device.
        Response online = call("PUT", "/api/v1/media/" + dune + "/progress", admin,
                Map.of("position", 60, "duration", 600, "completed", false));
        assertThat(online.json().path("applied").asBoolean()).isTrue();
        Instant finishedOffline = now.minus(Duration.ofHours(1)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Response offline = call("PUT", "/api/v1/media/" + dune + "/progress", admin,
                Map.of("position", 600, "duration", 600, "completed", true, "recordedAt", finishedOffline.toString()));
        assertThat(offline.json().path("applied").asBoolean()).isFalse();
        Map<String, Boolean> duneViewings = new java.util.HashMap<>();
        for (JsonNode h : call("GET", "/api/v1/history?pageSize=200", admin, null).json().path("items")) {
            if (dune.equals(h.path("mediaId").asText())) {
                duneViewings.put(h.path("watchedAt").asText(), h.path("completed").asBoolean());
            }
        }
        assertThat(duneViewings).hasSize(2).containsEntry(finishedOffline.toString(), true)
                .containsValue(false);

        assertThat(call("POST", "/api/v1/progress/batch", token("admin-svc", "history.read"),
                Map.of("updates", List.of())).status()).isEqualTo(403);
    }

    @Test
    void simultaneousFirstProgressUpdatesDoNotFail() throws Exception {
        Path dir = mediaDir("race-" + System.nanoTime(), "Heat (1995).mkv", 100);
        String libraryId = createLibrary("Race", dir, true);
        scan(libraryId);
        String mediaId = call("GET", "/api/v1/media?library=" + libraryId, admin, null).json().path("items").get(0)
                .path("id").asText();
        List<java.util.concurrent.CompletableFuture<Response>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            int position = i;
            calls.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return call("PUT", "/api/v1/media/" + mediaId + "/progress", admin, Map.of("position", position));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        for (java.util.concurrent.CompletableFuture<Response> c : calls) {
            assertThat(c.get().status()).isEqualTo(200);
        }
    }

    @Test
    void downloadOriginalWithResumeValidators() throws Exception {
        Path dir = mediaDir("download-" + System.nanoTime(), "Heat (1995).mkv", 5000);
        String libraryId = createLibrary("Downloads", dir, true);
        scan(libraryId);
        String mediaId = call("GET", "/api/v1/media?library=" + libraryId, admin, null).json().path("items").get(0)
                .path("id").asText();
        String path = "/api/v1/media/" + mediaId + "/download";

        HttpResponse<byte[]> full = download(path, admin);
        assertThat(full.statusCode()).isEqualTo(200);
        assertThat(full.body()).hasSize(5000);
        assertThat(full.headers().firstValue("Content-Length")).hasValue("5000");
        assertThat(full.headers().firstValue("Content-Disposition"))
                .hasValueSatisfying(v -> assertThat(v).startsWith("attachment").contains("Heat (1995).mkv"));
        assertThat(full.headers().firstValue("Accept-Ranges")).hasValue("bytes");
        String etag = full.headers().firstValue("ETag").orElseThrow();
        String lastModified = full.headers().firstValue("Last-Modified").orElseThrow();
        assertThat(etag).startsWith("\"");

        // Resuming the same version, by entity tag or by date.
        for (String validator : List.of(etag, lastModified)) {
            HttpResponse<byte[]> part = download(path, admin, "Range", "bytes=4000-", "If-Range", validator);
            assertThat(part.statusCode()).as(validator).isEqualTo(206);
            assertThat(part.headers().firstValue("Content-Range")).hasValue("bytes 4000-4999/5000");
            assertThat(part.body()).hasSize(1000);
        }
        assertThat(download(path, admin, "If-None-Match", etag).statusCode()).isEqualTo(304);

        // After the file is replaced, a resume of the old version gets the whole new file.
        Path file = dir.resolve("Heat (1995).mkv");
        Files.write(file, new byte[6000]);
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(Instant.now().plusSeconds(5)));
        HttpResponse<byte[]> replaced = download(path, admin, "Range", "bytes=4000-", "If-Range", etag);
        assertThat(replaced.statusCode()).isEqualTo(200);
        assertThat(replaced.body()).hasSize(6000);
        assertThat(replaced.headers().firstValue("ETag")).isNotEqualTo(java.util.Optional.of(etag));
        assertThat(download(path, admin, "Range", "bytes=0-9", "If-Range", lastModified).statusCode())
                .isEqualTo(200);

        // media.download is a separate scope; the USER role holds it by default.
        assertThat(download(path, token("bob-svc", "media.read media.stream")).statusCode()).isEqualTo(403);
        assertThat(download(path, token("bob-svc", "media.read media.download")).statusCode()).isEqualTo(200);
        assertThat(download("/api/v1/media/media_missing/download", admin).statusCode()).isEqualTo(404);
    }

    private HttpResponse<byte[]> download(String path, String bearer, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + bearer);
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void optimizedDownloadsArePreparedByJobs() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(ffmpegAvailable(), "ffmpeg is not installed");
        Path dir = Files.createDirectories(TMP.resolve("optimized-" + System.nanoTime()));
        Path video = dir.resolve("Alien (1979).mkv");
        Process ffmpeg = new ProcessBuilder("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=10",
                "-f", "lavfi", "-i", "sine", "-t", "2", "-c:v", "libx264", "-c:a", "aac", video.toString())
                .redirectErrorStream(true).start();
        assertThat(ffmpeg.waitFor()).isZero();
        Files.write(dir.resolve("Broken (2000).mkv"), new byte[2000]);
        String libraryId = createLibrary("Optimized", dir, true);
        scan(libraryId);
        JsonNode items = call("GET", "/api/v1/media?library=" + libraryId + "&sort=title", admin, null).json()
                .path("items");
        String alien = items.get(0).path("id").asText();
        String broken = items.get(1).path("id").asText();

        // The client plays the original: it is served as it is, and asking again returns the same download.
        Response direct = call("POST", "/api/v1/downloads", admin, Map.of("mediaId", alien,
                "capabilities", Map.of("containers", List.of("mkv"))));
        assertThat(direct.status()).isEqualTo(201);
        assertThat(direct.json().path("mode").asText()).isEqualTo("DIRECT_PLAY");
        assertThat(direct.json().path("status").asText()).isEqualTo("READY");
        Response again = call("POST", "/api/v1/downloads", admin, Map.of("mediaId", alien,
                "capabilities", Map.of("containers", List.of("mkv"))));
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.json().path("id").asText()).isEqualTo(direct.json().path("id").asText());
        HttpResponse<byte[]> original = download(direct.json().path("url").asText(), admin);
        assertThat(original.body()).isEqualTo(Files.readAllBytes(video));
        assertThat(original.headers().firstValue("Content-Disposition").orElseThrow()).contains("Alien (1979).mkv");

        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("Authorization", "Bearer " + admin);
        WebSocketSession socket = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                received.add(message.getPayload());
            }
        }, headers, URI.create("ws://localhost:" + port + "/api/v1/events")).get(10, TimeUnit.SECONDS);

        // An MP4-only client gets a remuxed copy, prepared in the background.
        Response remux = call("POST", "/api/v1/downloads", admin, Map.of("mediaId", alien,
                "capabilities", Map.of("containers", List.of("mp4"))));
        assertThat(remux.status()).isEqualTo(201);
        assertThat(remux.json().path("mode").asText()).isEqualTo("REMUX");
        String remuxId = remux.json().path("id").asText();
        JsonNode ready = awaitDownload(remuxId, "READY");
        assertThat(ready.path("progress").asInt()).isEqualTo(100);
        assertThat(ready.path("expiresAt").asText()).isNotBlank();
        HttpResponse<byte[]> mp4 = download(ready.path("url").asText(), admin);
        assertThat(mp4.statusCode()).isEqualTo(200);
        assertThat(mp4.headers().firstValue("Content-Type")).hasValue("video/mp4");
        assertThat(mp4.headers().firstValue("Content-Disposition").orElseThrow()).contains("Alien (1979).mp4");
        assertThat(new String(mp4.body(), 4, 4, StandardCharsets.US_ASCII)).isEqualTo("ftyp");
        assertThat(mp4.body()).hasSize(ready.path("size").asInt());
        String etag = mp4.headers().firstValue("ETag").orElseThrow();
        HttpResponse<byte[]> rest = download(ready.path("url").asText(), admin, "Range", "bytes=100-", "If-Range", etag);
        assertThat(rest.statusCode()).isEqualTo(206);
        assertThat(rest.body()).hasSize(mp4.body().length - 100);

        // A file ffmpeg cannot read fails, with the reason.
        String failedId = call("POST", "/api/v1/downloads", admin, Map.of("mediaId", broken,
                "capabilities", Map.of("containers", List.of("mp4")))).json().path("id").asText();
        JsonNode failed = awaitDownload(failedId, "FAILED");
        assertThat(failed.path("error").asText()).contains("ffmpeg");
        assertThat(call("GET", "/api/v1/downloads/" + failedId + "/file", admin, null).json().path("error").asText())
                .isEqualTo("DOWNLOAD_FAILED");

        // The owner is told when each preparation ends.
        Map<String, String> outcomes = new java.util.HashMap<>();
        long eventDeadline = System.currentTimeMillis() + 10_000;
        try {
            while (outcomes.size() < 2 && System.currentTimeMillis() < eventDeadline) {
                String message = received.poll(1, TimeUnit.SECONDS);
                JsonNode event = message == null ? null : json.readTree(message);
                if (event != null && event.path("type").asText().startsWith("DOWNLOAD_")) {
                    outcomes.put(event.path("data").path("downloadId").asText(), event.path("type").asText());
                }
            }
        } finally {
            socket.close();
        }
        assertThat(outcomes).containsEntry(remuxId, "DOWNLOAD_READY").containsEntry(failedId, "DOWNLOAD_FAILED");

        assertThat(call("GET", "/api/v1/downloads?pageSize=200", admin, null).json().path("items")
                .findValuesAsText("id")).contains(remuxId, failedId, direct.json().path("id").asText());
        assertThat(call("POST", "/api/v1/downloads", admin, Map.of("mediaId", alien,
                "capabilities", Map.of("containers", List.of("avi")))).json().path("error").asText())
                .isEqualTo("UNSUPPORTED_CAPABILITIES");

        // Downloads belong to the user who requested them.
        String bob = token("bob-svc", "media.read media.download");
        assertThat(call("GET", "/api/v1/downloads/" + remuxId, bob, null).status()).isEqualTo(404);
        assertThat(call("DELETE", "/api/v1/downloads/" + remuxId, bob, null).status()).isEqualTo(404);
        assertThat(call("GET", "/api/v1/downloads/" + remuxId, token("admin-svc", "media.read media.stream"), null)
                .status()).isEqualTo(403);

        // Deleting removes the prepared file.
        Path prepared = TMP.resolve("data").resolve("downloads").resolve(remuxId + ".mp4");
        assertThat(prepared).exists();
        assertThat(call("DELETE", "/api/v1/downloads/" + remuxId, admin, null).status()).isEqualTo(204);
        assertThat(prepared).doesNotExist();
        assertThat(call("GET", "/api/v1/downloads/" + remuxId, admin, null).status()).isEqualTo(404);
    }

    private JsonNode awaitDownload(String downloadId, String status) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (true) {
            JsonNode d = call("GET", "/api/v1/downloads/" + downloadId, admin, null).json();
            if (status.equals(d.path("status").asText()) || System.nanoTime() > deadline) {
                assertThat(d.path("status").asText()).as(d.toString()).isEqualTo(status);
                return d;
            }
            Thread.sleep(100);
        }
    }

    private static boolean ffmpegAvailable() {
        try {
            return new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Test
    void sidecarSubtitlesAreServedAsWebVtt() throws Exception {
        Path dir = mediaDir("subtitles-" + System.nanoTime(), "Amelie (2001).mkv", 100);
        Files.write(dir.resolve("Amelie (2001).fr.srt"), "1\r\n00:00:01,000 --> 00:00:02,000\r\nCafé\r\n"
                .getBytes(java.nio.charset.Charset.forName("windows-1252")));
        Files.writeString(dir.resolve("Amelie (2001).en.forced.vtt"), "WEBVTT\n\n00:01.000 --> 00:02.000\nHi\n");
        String libraryId = createLibrary("Subtitles", dir, true);
        scan(libraryId);
        String mediaId = call("GET", "/api/v1/media?library=" + libraryId, admin, null).json().path("items").get(0)
                .path("id").asText();

        JsonNode tracks = call("GET", "/api/v1/media/" + mediaId + "/subtitles", admin, null).json();
        assertThat(tracks.findValuesAsText("language")).containsExactly("en", "fr");
        assertThat(tracks.get(0).path("forced").asBoolean()).isTrue();
        assertThat(tracks.get(1).path("source").asText()).isEqualTo("SIDECAR");
        String french = tracks.get(1).path("id").asText();

        Response vtt = call("GET", "/api/v1/media/" + mediaId + "/subtitles/" + french, admin, null);
        assertThat(vtt.status()).isEqualTo(200);
        assertThat(vtt.header("Content-Type")).startsWith("text/vtt").containsIgnoringCase("charset=UTF-8");
        assertThat(vtt.body()).isEqualTo("WEBVTT\n\n1\n00:00:01.000 --> 00:00:02.000\nCafé\n\n");

        assertThat(call("GET", "/api/v1/media/" + mediaId + "/subtitles/s0000", admin, null).json().path("error")
                .asText()).isEqualTo("SUBTITLE_NOT_FOUND");
        assertThat(call("GET", "/api/v1/media/" + mediaId + "/subtitles/" + french, token("admin-svc", "media.read"),
                null).status()).isEqualTo(403);
        assertThat(call("GET", "/api/v1/media/" + mediaId + "/subtitles/" + french,
                token("admin-svc", "media.read media.download"), null).status()).isEqualTo(200);
    }

    @Test
    void privateLibrariesAreHiddenFromOtherUsers() throws Exception {
        Path dir = mediaDir("private-" + System.nanoTime(), "Secret Film (2001).mp4", 100);
        String libraryId = createLibrary("Private", dir, false);
        scan(libraryId);
        String bob = token("bob-svc", "library.read media.read media.stream");

        assertThat(call("GET", "/api/v1/libraries/" + libraryId, bob, null).status()).isEqualTo(404);
        Response libraries = call("GET", "/api/v1/libraries", bob, null);
        assertThat(libraries.json().path("items").findValuesAsText("id")).doesNotContain(libraryId);
        assertThat(libraries.json().path("items").findValues("paths")).allMatch(JsonNode::isNull);

        String mediaId = call("GET", "/api/v1/media?library=" + libraryId, admin, null).json()
                .path("items").get(0).path("id").asText();
        assertThat(call("GET", "/api/v1/media/" + mediaId, bob, null).status()).isEqualTo(404);
        assertThat(call("GET", "/api/v1/media/" + mediaId + "/stream", bob, null).status()).isEqualTo(404);
        assertThat(call("GET", "/api/v1/media?query=secret", bob, null).json().path("total").asInt()).isZero();

        // Bob lacks library.write, so the scope is stripped and the request is denied.
        String bobWrite = token("bob-svc", "library.write");
        assertThat(call("POST", "/api/v1/libraries/" + libraryId + "/scan", bobWrite, null).status()).isEqualTo(403);
    }

    @Test
    void personalAccessTokens() throws Exception {
        Response created = call("POST", "/api/v1/tokens", admin,
                Map.of("name", "My Script", "scopes", List.of("media.read"), "expiresIn", 3600));
        assertThat(created.status()).isEqualTo(201);
        String pat = created.json().path("token").asText();
        assertThat(pat).startsWith("ob_pat_");

        assertThat(call("GET", "/api/v1/media", pat, null).status()).isEqualTo(200);
        Response denied = call("GET", "/api/v1/libraries", pat, null);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.json().path("error").asText()).isEqualTo("INSUFFICIENT_SCOPE");

        Response listed = call("GET", "/api/v1/tokens", admin, null);
        assertThat(listed.body()).doesNotContain(pat);

        String bob = token("bob-svc", "profile media.read");
        Response escalate = call("POST", "/api/v1/tokens", bob, Map.of("name", "x", "scopes", List.of("library.write")));
        assertThat(escalate.status()).isEqualTo(403);

        assertThat(call("DELETE", "/api/v1/tokens/" + created.json().path("id").asText(), admin, null).status())
                .isEqualTo(204);
        Response revoked = call("GET", "/api/v1/media", pat, null);
        assertThat(revoked.status()).isEqualTo(401);
        assertThat(revoked.json().path("error").asText()).isEqualTo("INVALID_TOKEN");
    }

    @Test
    void deviceLinking() throws Exception {
        Response started = call("POST", "/api/v1/device-links", null, Map.of("name", "Kodi (living room)"));
        assertThat(started.status()).isEqualTo(201);
        String deviceCode = started.json().path("deviceCode").asText();
        String userCode = started.json().path("userCode").asText();
        assertThat(userCode).matches("[A-Z]{4}-[A-Z]{4}");
        assertThat(started.json().path("verificationUriComplete").asText()).endsWith("/#/link/" + userCode);

        Response pending = call("POST", "/api/v1/device-links/token", null, Map.of("deviceCode", deviceCode));
        assertThat(pending.json().path("status").asText()).isEqualTo("PENDING");
        assertThat(call("GET", "/api/v1/device-links/" + userCode, null, null).status()).isEqualTo(401);

        Response shown = call("GET", "/api/v1/device-links/" + userCode.toLowerCase().replace("-", "%20"), admin, null);
        assertThat(shown.json().path("name").asText()).isEqualTo("Kodi (living room)");
        assertThat(call("POST", "/api/v1/device-links/" + userCode + "/approve", admin, null).status()).isEqualTo(200);

        Response approved = call("POST", "/api/v1/device-links/token", null, Map.of("deviceCode", deviceCode));
        assertThat(approved.json().path("status").asText()).isEqualTo("APPROVED");
        String token = approved.json().path("token").asText();
        assertThat(token).startsWith("ob_pat_");
        assertThat(call("GET", "/api/v1/media", token, null).status()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/continue-watching", token, null).status()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/tokens", token, null).status()).isEqualTo(403);

        assertThat(approved.json().path("tokenId").asText()).startsWith("token_");
        // The token is handed out once.
        assertThat(call("POST", "/api/v1/device-links/token", null, Map.of("deviceCode", deviceCode)).status())
                .isEqualTo(404);
        assertThat(call("GET", "/api/v1/tokens", admin, null).json().path("items").findValuesAsText("name"))
                .contains("Kodi (living room)");
        // Signing out on the device revokes its own token, without needing the profile scope.
        assertThat(call("DELETE", "/api/v1/tokens/current", token, null).status()).isEqualTo(204);
        assertThat(call("GET", "/api/v1/media", token, null).status()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/tokens", admin, null).json().path("items").findValuesAsText("name"))
                .doesNotContain("Kodi (living room)");
        assertThat(call("DELETE", "/api/v1/tokens/current", admin, null).status()).isEqualTo(400);

        Response declined = call("POST", "/api/v1/device-links", null, Map.of("name", "Unknown TV"));
        String declinedCode = declined.json().path("userCode").asText();
        assertThat(call("POST", "/api/v1/device-links/" + declinedCode + "/deny", admin, null).status()).isEqualTo(204);
        Response denied = call("POST", "/api/v1/device-links/token", null,
                Map.of("deviceCode", declined.json().path("deviceCode").asText()));
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.json().path("error").asText()).isEqualTo("DEVICE_LINK_DENIED");
        assertThat(call("GET", "/api/v1/device-links/BBBB-BBBB", admin, null).status()).isEqualTo(404);
    }

    @Test
    void resumableUpload() throws Exception {
        Path dir = Files.createDirectories(TMP.resolve("upload-lib-" + System.nanoTime()));
        String libraryId = createLibrary("Uploads", dir, true);
        byte[] content = new byte[1500];
        new Random(1).nextBytes(content);

        Response created = call("POST", "/api/v1/uploads", admin,
                Map.of("filename", "../Arrival (2016).mp4", "size", content.length, "libraryId", libraryId));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().path("chunkSize").asInt()).isEqualTo(1024);
        String id = created.json().path("id").asText();

        assertThat(chunk(id, content, 0, 1023).json().path("received").asInt()).isEqualTo(1024);
        assertThat(call("POST", "/api/v1/uploads/" + id + "/complete", admin, null).json().path("error").asText())
                .isEqualTo("UPLOAD_INCOMPLETE");
        assertThat(chunk(id, content, 1200, 1499).status()).isEqualTo(409);
        assertThat(chunk(id, content, 1024, 1499).json().path("received").asInt()).isEqualTo(1500);

        Response complete = call("POST", "/api/v1/uploads/" + id + "/complete", admin, null);
        assertThat(complete.json().path("status").asText()).isEqualTo("PROCESSING");
        assertThat(complete.json().has("mediaId")).isTrue();

        JsonNode status = null;
        for (int i = 0; i < 50; i++) {
            status = call("GET", "/api/v1/uploads/" + id, admin, null).json();
            if ("COMPLETED".equals(status.path("status").asText())) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(status.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(status.path("mediaId").asText()).startsWith("media_");
        assertThat(Files.readAllBytes(dir.resolve("Arrival (2016).mp4"))).isEqualTo(content);

        Response bad = call("POST", "/api/v1/uploads", admin, Map.of("filename", "run.sh", "size", 5, "libraryId", libraryId));
        assertThat(bad.status()).isEqualTo(415);
    }

    @Test
    void eventsAreDeliveredOverWebSocket() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("Authorization", "Bearer " + admin);
        WebSocketSession socket = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                received.add(message.getPayload());
            }
        }, headers, URI.create("ws://localhost:" + port + "/api/v1/events")).get(10, TimeUnit.SECONDS);
        try {
            Path dir = mediaDir("events-" + System.nanoTime(), "Dune (2021).mkv", 10);
            String libraryId = createLibrary("Events", dir, true);
            scan(libraryId);
            List<String> types = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 10_000;
            while (!types.contains("LIBRARY_SCAN_COMPLETED") && System.currentTimeMillis() < deadline) {
                String message = received.poll(1, TimeUnit.SECONDS);
                if (message != null) {
                    types.add(json.readTree(message).path("type").asText());
                }
            }
            assertThat(types).contains("LIBRARY_SCAN_STARTED", "MEDIA_ADDED", "LIBRARY_SCAN_COMPLETED");
        } finally {
            socket.close();
        }

        HttpResponse<String> anonymous = http.send(HttpRequest.newBuilder(uri("/api/v1/events")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
    }

    @Test
    void pluginLifecycle() throws Exception {
        ByteArrayOutputStream jar = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(jar)) {
            out.putNextEntry(new JarEntry("META-INF/openbased-plugin.properties"));
            out.write("id=echo\nname=Echo\nversion=1.0.0\nmain=org.openbased.testplugin.EchoPlugin\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        HttpResponse<String> installed = http.send(HttpRequest.newBuilder(uri("/api/v1/plugins"))
                .header("Authorization", "Bearer " + admin).header("Content-Type", "application/java-archive")
                .POST(HttpRequest.BodyPublishers.ofByteArray(jar.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(installed.statusCode()).isEqualTo(201);
        assertThat(json.readTree(installed.body()).path("enabled").asBoolean()).isFalse();

        assertThat(call("GET", "/api/v1/plugins/echo/status", admin, null).status()).isEqualTo(404);
        assertThat(call("POST", "/api/v1/plugins/echo/enable", admin, null).json().path("running").asBoolean()).isTrue();

        Response status = call("GET", "/api/v1/plugins/echo/status", admin, null);
        assertThat(status.status()).isEqualTo(200);
        assertThat(status.json().path("status").asText()).isEqualTo("OK");
        assertThat(call("GET", "/api/v1/plugins/echo/whoami", token("admin-svc", "profile"), null).status())
                .isEqualTo(403);

        Response metadata = call("GET", "/api/v1/metadata/search?type=MOVIE&query=Interstellar&provider=echo", admin, null);
        assertThat(metadata.json().path("provider").asText()).isEqualTo("echo");
        assertThat(metadata.json().path("results").get(0).path("title").asText()).isEqualTo("Interstellar");

        Response list = call("GET", "/api/v1/plugins", admin, null);
        assertThat(list.json().path("items").findValuesAsText("id")).contains("echo");
        assertThat(call("GET", "/api/v1/plugins", token("bob-svc", "plugins.manage"), null).status()).isEqualTo(403);

        call("POST", "/api/v1/plugins/echo/disable", admin, null);
        assertThat(call("GET", "/api/v1/plugins/echo/status", admin, null).status()).isEqualTo(404);
        assertThat(call("DELETE", "/api/v1/plugins/echo", admin, null).status()).isEqualTo(204);
    }

    @Test
    void nativeAppSignInWithRotatingRefreshTokens() throws Exception {
        String redirect = "org.openbased.android:/oauth2redirect";
        JsonNode first = signIn("openbased-android", redirect, "bob", "bobpassword1");
        String access = first.path("access_token").asText();
        String refresh = first.path("refresh_token").asText();
        assertThat(refresh).isNotBlank();
        assertThat(call("GET", "/api/v1/users/me", access, null).json().path("username").asText()).isEqualTo("bob");

        // Tokens are persisted, with secrets hashed.
        assertThat(authorizationRepository.findAll()).anySatisfy(a -> {
            assertThat(a.getPrincipalName()).isEqualTo("bob");
            assertThat(a.getRefreshTokenValue()).startsWith("sha256:").isNotEqualTo(refresh);
        });

        // Refreshing needs only client_id and rotates the refresh token; the old access token stops working.
        Response rotated = tokenRequest(Map.of("grant_type", "refresh_token", "refresh_token", refresh,
                "client_id", "openbased-android"));
        assertThat(rotated.status()).as(rotated.body()).isEqualTo(200);
        String access2 = rotated.json().path("access_token").asText();
        String refresh2 = rotated.json().path("refresh_token").asText();
        assertThat(refresh2).isNotBlank().isNotEqualTo(refresh);
        assertThat(call("GET", "/api/v1/users/me", access2, null).status()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/users/me", access, null).status()).isEqualTo(401);

        // Using a rotated refresh token again revokes the whole sign-in.
        Response reused = tokenRequest(Map.of("grant_type", "refresh_token", "refresh_token", refresh,
                "client_id", "openbased-android"));
        assertThat(reused.status()).isEqualTo(400);
        assertThat(reused.json().path("error").asText()).isEqualTo("invalid_grant");
        assertThat(tokenRequest(Map.of("grant_type", "refresh_token", "refresh_token", refresh2,
                "client_id", "openbased-android")).status()).isEqualTo(400);
        assertThat(call("GET", "/api/v1/users/me", access2, null).status()).isEqualTo(401);

        // Signing out: a public client revokes its refresh token with only client_id.
        JsonNode second = signIn("openbased-android", redirect, "bob", "bobpassword1");
        Response revoked = formPost("/oauth2/revoke", Map.of("token", second.path("refresh_token").asText(),
                "token_type_hint", "refresh_token", "client_id", "openbased-android"));
        assertThat(revoked.status()).as(revoked.body()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/users/me", second.path("access_token").asText(), null).status()).isEqualTo(401);
        assertThat(tokenRequest(Map.of("grant_type", "refresh_token", "refresh_token",
                second.path("refresh_token").asText(), "client_id", "openbased-android")).status()).isEqualTo(400);

        // Unknown clients and clients that need a secret cannot use the public flow.
        assertThat(tokenRequest(Map.of("grant_type", "refresh_token", "refresh_token", "x", "client_id", "nope"))
                .status()).isEqualTo(401);
        assertThat(tokenRequest(Map.of("grant_type", "refresh_token", "refresh_token", "x", "client_id", "bob-svc"))
                .status()).isEqualTo(401);
    }

    // --- helpers

    /** Runs Authorization Code + PKCE through the sign-in page, as a browser would, and returns the token response. */
    private JsonNode signIn(String clientId, String redirectUri, String username, String password) throws Exception {
        HttpClient browser = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
                .cookieHandler(new java.net.CookieManager()).build();
        byte[] random = new byte[32];
        new java.security.SecureRandom().nextBytes(random);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String authorize = "/oauth2/authorize?" + form(Map.of("client_id", clientId, "redirect_uri", redirectUri,
                "response_type", "code", "scope", "openid profile media.read", "state", "s1",
                "code_challenge", challenge, "code_challenge_method", "S256"));
        HttpResponse<String> step = browser.send(HttpRequest.newBuilder(uri(authorize)).header("Accept", "text/html")
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(step.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).endsWith("/login"));
        String loginPage = browser.send(HttpRequest.newBuilder(uri("/login")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        java.util.regex.Matcher csrf = java.util.regex.Pattern.compile("name=\"_csrf\" type=\"hidden\" value=\"([^\"]+)\"")
                .matcher(loginPage);
        assertThat(csrf.find()).isTrue();
        step = browser.send(HttpRequest.newBuilder(uri("/login")).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("username", username, "password", password,
                        "_csrf", csrf.group(1))))).build(), HttpResponse.BodyHandlers.ofString());
        String next = step.headers().firstValue("Location").orElseThrow();
        step = browser.send(HttpRequest.newBuilder(URI.create(next)).header("Accept", "text/html").build(),
                HttpResponse.BodyHandlers.ofString());
        String callback = step.headers().firstValue("Location").orElseThrow();
        assertThat(callback).startsWith(redirectUri + "?");
        String code = java.net.URLDecoder.decode(callback.replaceAll(".*[?&]code=([^&]+).*", "$1"), StandardCharsets.UTF_8);
        Response token = tokenRequest(Map.of("grant_type", "authorization_code", "code", code,
                "redirect_uri", redirectUri, "client_id", clientId, "code_verifier", verifier));
        assertThat(token.status()).as(token.body()).isEqualTo(200);
        return token.json();
    }

    private Response tokenRequest(Map<String, String> params) throws Exception {
        return formPost("/oauth2/token", params);
    }

    private Response formPost(String path, Map<String, String> params) throws Exception {
        return toResponse(http.send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(params))).build(), HttpResponse.BodyHandlers.ofString()));
    }

    private static String form(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private Path mediaDir(String name, String file, int size) throws IOException {
        Path dir = Files.createDirectories(TMP.resolve(name));
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        Files.write(dir.resolve(file), bytes);
        return dir;
    }

    private String createLibrary(String name, Path dir, boolean allUsers) throws Exception {
        Response created = call("POST", "/api/v1/libraries", admin, Map.of("name", name, "type", "MOVIES",
                "paths", List.of(dir.toString()), "access", Map.of("allUsers", allUsers, "userIds", List.of())));
        assertThat(created.status()).isEqualTo(201);
        return created.json().path("id").asText();
    }

    private void scan(String libraryId) throws Exception {
        Response started = call("POST", "/api/v1/libraries/" + libraryId + "/scan", admin, null);
        assertThat(started.status()).isEqualTo(202);
        String jobId = started.json().path("jobId").asText();
        for (int i = 0; i < 100; i++) {
            String status = call("GET", "/api/v1/jobs/" + jobId, admin, null).json().path("status").asText();
            if (status.equals("COMPLETED")) {
                return;
            }
            assertThat(status).isIn("QUEUED", "RUNNING");
            Thread.sleep(100);
        }
        throw new AssertionError("Scan did not finish");
    }

    private Response chunk(String uploadId, byte[] content, int start, int end) throws Exception {
        byte[] part = java.util.Arrays.copyOfRange(content, start, end + 1);
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/api/v1/uploads/" + uploadId))
                .header("Authorization", "Bearer " + admin)
                .header("Content-Type", "application/octet-stream")
                .header("Content-Range", "bytes " + start + "-" + end + "/" + content.length)
                .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(part)).build(),
                HttpResponse.BodyHandlers.ofString());
        return toResponse(response);
    }

    private String token(String clientId, String scope) throws Exception {
        String form = "grant_type=client_credentials&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8);
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/oauth2/token"))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((clientId + ":secret").getBytes()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("access_token").asText();
    }

    private Response call(String method, String path, String bearer, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return toResponse(http.send(request.build(), HttpResponse.BodyHandlers.ofString()));
    }

    private Response toResponse(HttpResponse<String> response) throws IOException {
        String body = response.body();
        JsonNode node = body == null || body.isBlank() ? json.nullNode() : safeParse(body);
        return new Response(response.statusCode(), response.headers().map(), body, node);
    }

    private JsonNode safeParse(String body) {
        try {
            return json.readTree(body);
        } catch (IOException e) {
            return json.nullNode();
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
