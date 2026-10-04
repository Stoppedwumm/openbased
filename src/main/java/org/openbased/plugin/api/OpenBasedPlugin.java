package org.openbased.plugin.api;

/** Entry point of a plugin. */
public interface OpenBasedPlugin {

    /** Called when the plugin is enabled. Register providers, endpoints, listeners and jobs here. */
    void start(PluginContext context) throws Exception;

    /** Called when the plugin is disabled or the server shuts down. Registrations are removed automatically. */
    default void stop() throws Exception {
    }
}
