package org.openbased.plugin.api;

import java.util.Map;
import java.util.Optional;

public interface PluginSettings {

    Optional<String> get(String key);

    void set(String key, String value);

    Map<String, String> all();
}
