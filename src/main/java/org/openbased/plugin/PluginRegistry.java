package org.openbased.plugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.openbased.plugin.api.MediaProcessor;
import org.openbased.plugin.api.MetadataProvider;
import org.openbased.plugin.api.PluginEndpoint;
import org.springframework.stereotype.Component;

/**
 * Extension points shared by built-in features and plugins. Built-in metadata providers register here
 * too, so plugins and the core are treated the same way.
 */
@Component
public class PluginRegistry {

    public record Endpoint(String pluginId, String method, String path, String requiredScope, PluginEndpoint handler) {
    }

    public record Owned<T>(String ownerId, T value) {
    }

    private final List<Owned<MetadataProvider>> metadataProviders = new CopyOnWriteArrayList<>();
    private final List<Owned<MediaProcessor>> mediaProcessors = new CopyOnWriteArrayList<>();
    private final Map<String, Endpoint> endpoints = new ConcurrentHashMap<>();

    public void addMetadataProvider(String ownerId, MetadataProvider provider) {
        metadataProviders.add(new Owned<>(ownerId, provider));
    }

    public void addMediaProcessor(String ownerId, MediaProcessor processor) {
        mediaProcessors.add(new Owned<>(ownerId, processor));
    }

    public void addEndpoint(Endpoint endpoint) {
        String key = key(endpoint.pluginId(), endpoint.method(), endpoint.path());
        if (endpoints.putIfAbsent(key, endpoint) != null) {
            throw new IllegalArgumentException("Endpoint already registered: " + endpoint.method() + " " + endpoint.path());
        }
    }

    public List<MetadataProvider> metadataProviders() {
        return metadataProviders.stream().map(Owned::value).toList();
    }

    public Optional<MetadataProvider> metadataProvider(String id) {
        return metadataProviders().stream().filter(p -> p.id().equals(id)).findFirst();
    }

    public List<Owned<MediaProcessor>> mediaProcessors() {
        return List.copyOf(mediaProcessors);
    }

    public Optional<Endpoint> endpoint(String pluginId, String method, String path) {
        return Optional.ofNullable(endpoints.get(key(pluginId, method, path)));
    }

    public boolean hasEndpoints(String pluginId, String path) {
        return endpoints.values().stream().anyMatch(e -> e.pluginId().equals(pluginId) && e.path().equals(path));
    }

    /** Removes everything registered by the owner. */
    public void removeOwner(String ownerId) {
        metadataProviders.removeIf(o -> o.ownerId().equals(ownerId));
        mediaProcessors.removeIf(o -> o.ownerId().equals(ownerId));
        endpoints.values().removeIf(e -> e.pluginId().equals(ownerId));
    }

    private static String key(String pluginId, String method, String path) {
        return pluginId + " " + method.toUpperCase(java.util.Locale.ROOT) + " " + path;
    }
}
