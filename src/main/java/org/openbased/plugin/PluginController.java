package org.openbased.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.openbased.common.ApiException;
import org.openbased.plugin.api.PluginEndpoint;
import org.openbased.plugin.api.PluginSettings;
import org.openbased.security.AccessService;
import org.openbased.security.ApiPrincipal;
import org.openbased.security.Scopes;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/plugins")
@Tag(name = "Plugins")
public class PluginController {

    private static final int MAX_PLUGIN_REQUEST_BYTES = 10 * 1024 * 1024;

    private final PluginManager plugins;
    private final PluginRegistry registry;
    private final AccessService access;

    public PluginController(PluginManager plugins, PluginRegistry registry, AccessService access) {
        this.plugins = plugins;
        this.registry = registry;
        this.access = access;
    }

    public record PluginResponse(String id, String name, String version, boolean enabled, boolean running,
            String sha256, String lastError, Instant installedAt) {
    }

    public record PluginList(List<PluginResponse> items) {
    }

    private PluginResponse toResponse(PluginRecord r) {
        return new PluginResponse(r.getId(), r.getName(), r.getVersion(), r.isEnabled(), plugins.isRunning(r.getId()),
                r.getSha256(), r.getLastError(), r.getInstalledAt());
    }

    @GetMapping
    @Operation(summary = "List installed plugins")
    public PluginList list() {
        access.requireAny(Scopes.PLUGINS_MANAGE, Scopes.PLUGINS_READ);
        return new PluginList(plugins.list().stream().map(this::toResponse).toList());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Install a plugin JAR (multipart field 'file'). Plugins are installed disabled")
    public ResponseEntity<PluginResponse> install(@RequestPart("file") MultipartFile file) throws IOException {
        access.require(Scopes.PLUGINS_MANAGE);
        try (InputStream in = file.getInputStream()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(plugins.install(in)));
        }
    }

    @PostMapping(consumes = "application/java-archive")
    @Operation(summary = "Install a plugin JAR sent as the raw request body")
    public ResponseEntity<PluginResponse> installRaw(HttpServletRequest request) throws IOException {
        access.require(Scopes.PLUGINS_MANAGE);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(plugins.install(request.getInputStream())));
    }

    @PostMapping("/{pluginId}/enable")
    @Operation(summary = "Enable a plugin")
    public PluginResponse enable(@PathVariable String pluginId) {
        access.require(Scopes.PLUGINS_MANAGE);
        return toResponse(plugins.enable(pluginId));
    }

    @PostMapping("/{pluginId}/disable")
    @Operation(summary = "Disable a plugin")
    public PluginResponse disable(@PathVariable String pluginId) {
        access.require(Scopes.PLUGINS_MANAGE);
        return toResponse(plugins.disable(pluginId));
    }

    @DeleteMapping("/{pluginId}")
    @Operation(summary = "Uninstall a plugin")
    public ResponseEntity<Void> uninstall(@PathVariable String pluginId) {
        access.require(Scopes.PLUGINS_MANAGE);
        plugins.uninstall(pluginId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{pluginId}/settings")
    @Operation(summary = "Get a plugin's settings")
    public Map<String, String> settings(@PathVariable String pluginId) {
        access.require(Scopes.PLUGINS_MANAGE);
        plugins.get(pluginId);
        return plugins.settings(pluginId).all();
    }

    @PutMapping("/{pluginId}/settings")
    @Operation(summary = "Replace a plugin's settings")
    public Map<String, String> updateSettings(@PathVariable String pluginId, @RequestBody Map<String, String> values) {
        access.require(Scopes.PLUGINS_MANAGE);
        plugins.get(pluginId);
        PluginSettings settings = plugins.settings(pluginId);
        settings.all().keySet().stream().filter(k -> !values.containsKey(k)).forEach(k -> settings.set(k, null));
        values.forEach(settings::set);
        return settings.all();
    }

    /** Routes {@code /api/v1/plugins/{pluginId}/...} to endpoints registered by the plugin. */
    @RequestMapping("/{pluginId}/**")
    @Operation(summary = "Plugin-defined endpoints")
    public ResponseEntity<?> dispatch(@PathVariable String pluginId, HttpServletRequest request) throws Exception {
        plugins.requireRunning(pluginId);
        String prefix = "/api/v1/plugins/" + pluginId + "/";
        String uri = request.getRequestURI();
        String path = uri.startsWith(prefix) ? uri.substring(prefix.length()) : "";
        PluginRegistry.Endpoint endpoint = registry.endpoint(pluginId, request.getMethod(), path).orElseThrow(() ->
                registry.hasEndpoints(pluginId, path)
                        ? new ApiException(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method not allowed.")
                        : ApiException.notFound("NOT_FOUND", "No endpoint " + uri));
        if (endpoint.requiredScope() != null) {
            access.require(endpoint.requiredScope());
        }
        ApiPrincipal principal = access.principal();
        byte[] body = request.getInputStream().readNBytes(MAX_PLUGIN_REQUEST_BYTES + 1);
        if (body.length > MAX_PLUGIN_REQUEST_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "The request body is too large.");
        }
        Map<String, List<String>> query = new LinkedHashMap<>();
        request.getParameterMap().forEach((k, v) -> query.put(k, List.of(v)));
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            if (!name.equalsIgnoreCase("Authorization") && !name.equalsIgnoreCase("Cookie")) {
                headers.put(name.toLowerCase(java.util.Locale.ROOT), request.getHeader(name));
            }
        }
        PluginEndpoint.Response response = endpoint.handler().handle(new PluginEndpoint.Request(request.getMethod(),
                path, query, headers, body, principal.userId(), principal.scopes()));
        if (response == null) {
            return ResponseEntity.noContent().build();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.status());
        if (response.contentType() != null) {
            builder.contentType(MediaType.parseMediaType(response.contentType()));
        }
        return response.body() == null ? builder.build() : builder.body(response.body());
    }
}
