package org.openbased.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

import org.openbased.plugin.api.PluginSettings;

/** Plugin settings persisted as a properties file in the plugin's data directory. */
final class FilePluginSettings implements PluginSettings {

    private final Path file;

    FilePluginSettings(Path file) {
        this.file = file;
    }

    @Override
    public synchronized Optional<String> get(String key) {
        return Optional.ofNullable(load().getProperty(key));
    }

    @Override
    public synchronized void set(String key, String value) {
        Properties props = load();
        if (value == null) {
            props.remove(key);
        } else {
            props.setProperty(key, value);
        }
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, null);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized Map<String, String> all() {
        Map<String, String> all = new TreeMap<>();
        load().forEach((k, v) -> all.put(k.toString(), v.toString()));
        return all;
    }

    private Properties load() {
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return props;
    }
}
