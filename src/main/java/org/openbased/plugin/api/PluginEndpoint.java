package org.openbased.plugin.api;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Handles a REST request routed to a plugin. */
@FunctionalInterface
public interface PluginEndpoint {

    Response handle(Request request) throws Exception;

    /**
     * @param path the path below {@code /api/v1/plugins/{pluginId}/}
     * @param userId the calling user, or {@code null} for clients without a user
     */
    record Request(String method, String path, Map<String, List<String>> query, Map<String, String> headers,
            byte[] body, String userId, Set<String> scopes) {
    }

    /**
     * @param body serialized as JSON unless it is a {@code byte[]} or {@code String}
     */
    record Response(int status, String contentType, Object body) {

        public static Response json(Object body) {
            return new Response(200, "application/json", body);
        }

        public static Response status(int status) {
            return new Response(status, null, null);
        }
    }
}
