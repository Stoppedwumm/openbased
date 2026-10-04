/**
 * The stable API that OpenBased plugins compile against.
 *
 * <p>A plugin is a JAR containing {@code META-INF/openbased-plugin.properties}:
 *
 * <pre>
 * id=com.example.plugin
 * name=Example Plugin
 * version=1.0.0
 * main=com.example.ExamplePlugin
 * </pre>
 *
 * where {@code main} names a public class implementing {@link org.openbased.plugin.api.OpenBasedPlugin}
 * with a public no-argument constructor. Plugins are installed disabled; enabling one calls
 * {@link org.openbased.plugin.api.OpenBasedPlugin#start(PluginContext)}, and everything it registered is
 * removed again when it is disabled.
 */
package org.openbased.plugin.api;
