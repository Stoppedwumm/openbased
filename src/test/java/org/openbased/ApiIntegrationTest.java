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

        // The token is handed out once.
        assertThat(call("POST", "/api/v1/device-links/token", null, Map.of("deviceCode", deviceCode)).status())
                .isEqualTo(404);
        assertThat(call("GET", "/api/v1/tokens", admin, null).json().path("items").findValuesAsText("name"))
                .contains("Kodi (living room)");

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

    // --- helpers

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
