package org.openbased.plugin.api;

import java.nio.file.Path;
import java.time.Duration;

/** Services available to a running plugin. */
public interface PluginContext {

    String pluginId();

    /** A private directory for the plugin's own files. */
    Path dataDirectory();

    /** Persistent key/value settings, also editable via {@code /api/v1/plugins/{pluginId}/settings}. */
    PluginSettings settings();

    org.slf4j.Logger logger();

    void registerMetadataProvider(MetadataProvider provider);

    void registerMediaProcessor(MediaProcessor processor);

    void registerEventListener(EventListener listener);

    /**
     * Exposes an endpoint at {@code /api/v1/plugins/{pluginId}/{path}}.
     *
     * @param method HTTP method, e.g. {@code GET}
     * @param path path below the plugin's prefix, without a leading slash, e.g. {@code status}
     * @param requiredScope scope (and permission) callers must hold, or {@code null} for any authenticated caller
     */
    void registerEndpoint(String method, String path, String requiredScope, PluginEndpoint handler);

    /** Runs a task repeatedly while the plugin is enabled. */
    void scheduleJob(String name, Duration interval, Runnable task);
}
