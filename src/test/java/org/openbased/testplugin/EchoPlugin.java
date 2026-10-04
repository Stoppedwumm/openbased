package org.openbased.testplugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.openbased.plugin.api.MetadataProvider;
import org.openbased.plugin.api.OpenBasedPlugin;
import org.openbased.plugin.api.PluginContext;
import org.openbased.plugin.api.PluginEndpoint;

/** Plugin used by the integration tests. */
public class EchoPlugin implements OpenBasedPlugin {

    @Override
    public void start(PluginContext context) {
        context.registerEndpoint("GET", "status", null, request -> PluginEndpoint.Response.json(Map.of("status", "OK")));
        context.registerEndpoint("GET", "whoami", "media.read",
                request -> PluginEndpoint.Response.json(Map.of("userId", String.valueOf(request.userId()))));
        context.registerMetadataProvider(new MetadataProvider() {
            @Override
            public String id() {
                return "echo";
            }

            @Override
            public List<Result> search(String type, String query, Integer year) {
                return List.of(new Result("echo-1", query, year, null));
            }

            @Override
            public Optional<Details> fetch(String type, String externalId) {
                return Optional.of(new Details(externalId, "Echoed", null, 2001, "From the echo plugin",
                        List.of("Drama"), 100, null, null, Map.of()));
            }
        });
    }
}
