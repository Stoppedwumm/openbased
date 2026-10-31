package org.openbased.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;

import org.openbased.common.ApiException;
import org.openbased.config.OpenBasedProperties;
import org.openbased.event.Event;
import org.openbased.event.EventBus;
import org.openbased.plugin.api.EventListener;
import org.openbased.plugin.api.MediaProcessor;
import org.openbased.plugin.api.MetadataProvider;
import org.openbased.plugin.api.OpenBasedPlugin;
import org.openbased.plugin.api.PluginContext;
import org.openbased.plugin.api.PluginEndpoint;
import org.openbased.plugin.api.PluginSettings;
import org.openbased.security.Scopes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Installs, enables, disables and removes plugins. Plugins are installed disabled; enabling one loads its
 * JAR in a dedicated class loader. Only callers with {@code plugins.manage} reach these operations,
 * because a plugin runs with the server's privileges.
 */
@Service
public class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);
    private static final Pattern PLUGIN_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{1,127}$");
    private static final Pattern ENDPOINT_PATH = Pattern.compile("^[A-Za-z0-9._~-]+(/[A-Za-z0-9._~-]+)*$");
    private static final java.util.Set<String> RESERVED_PATHS = java.util.Set.of("enable", "disable", "settings");
    private static final String DESCRIPTOR = "META-INF/openbased-plugin.properties";

    private final PluginRecordRepository records;
    private final PluginRegistry registry;
    private final EventBus events;
    private final OpenBasedProperties properties;
    private final Map<String, Runtime> running = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "openbased-plugin-jobs");
        t.setDaemon(true);
        return t;
    });

    public PluginManager(PluginRecordRepository records, PluginRegistry registry, EventBus events,
            OpenBasedProperties properties) {
        this.records = records;
        this.registry = registry;
        this.events = events;
        this.properties = properties;
    }

    private static final class Runtime {
        final OpenBasedPlugin plugin;
        final URLClassLoader classLoader;
        final List<Runnable> cleanups = new CopyOnWriteArrayList<>();

        Runtime(OpenBasedPlugin plugin, URLClassLoader classLoader) {
            this.plugin = plugin;
            this.classLoader = classLoader;
        }
    }

    public boolean isRunning(String pluginId) {
        return running.containsKey(pluginId);
    }

    @org.springframework.context.event.EventListener(ApplicationReadyEvent.class)
    void startEnabledPlugins() {
        for (PluginRecord record : records.findByEnabledTrue()) {
            try {
                start(record);
            } catch (RuntimeException e) {
                log.error("Plugin {} failed to start", record.getId(), e);
                record.setLastError(message(e));
                records.save(record);
            }
        }
    }

    public List<PluginRecord> list() {
        return records.findAll().stream().sorted((a, b) -> a.getId().compareTo(b.getId())).toList();
    }

    public PluginRecord get(String pluginId) {
        return records.findById(pluginId)
                .orElseThrow(() -> ApiException.notFound("PLUGIN_NOT_FOUND", "The requested plugin does not exist."));
    }

    /** Validates and stores an uploaded plugin JAR. The plugin is installed disabled. */
    public synchronized PluginRecord install(InputStream upload) {
        Path dir = pluginDir();
        Path temp = null;
        try {
            Files.createDirectories(dir);
            temp = Files.createTempFile(dir, "upload-", ".jar");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(upload, digest)) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            Properties descriptor = readDescriptor(temp);
            String id = descriptor.getProperty("id", "").trim();
            String name = descriptor.getProperty("name", id).trim();
            String version = descriptor.getProperty("version", "").trim();
            String main = descriptor.getProperty("main", "").trim();
            if (!PLUGIN_ID.matcher(id).matches()) {
                throw ApiException.unprocessable("INVALID_PLUGIN", "The plugin id must match " + PLUGIN_ID.pattern());
            }
            if (version.isEmpty() || main.isEmpty()) {
                throw ApiException.unprocessable("INVALID_PLUGIN", "The plugin descriptor must define version and main.");
            }
            if (records.existsById(id)) {
                throw ApiException.conflict("PLUGIN_EXISTS", "A plugin with id " + id + " is already installed.");
            }
            Path target = dir.resolve(id + "-" + version.replaceAll("[^A-Za-z0-9._-]", "_") + ".jar");
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
            PluginRecord record = new PluginRecord();
            record.setId(id);
            record.setName(name);
            record.setVersion(version);
            record.setMainClass(main);
            record.setJarPath(target.toAbsolutePath().toString());
            record.setSha256(HexFormat.of().formatHex(digest.digest()));
            record.setEnabled(false);
            record.setInstalledAt(Instant.now());
            return records.save(record);
        } catch (IOException e) {
            throw ApiException.unprocessable("INVALID_PLUGIN", "The upload is not a valid plugin JAR.");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Best effort.
                }
            }
        }
    }

    private static Properties readDescriptor(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile(), true)) {
            JarEntry entry = file.getJarEntry(DESCRIPTOR);
            if (entry == null) {
                throw ApiException.unprocessable("INVALID_PLUGIN", "The JAR has no " + DESCRIPTOR + ".");
            }
            Properties props = new Properties();
            try (InputStream in = file.getInputStream(entry)) {
                props.load(in);
            }
            return props;
        }
    }

    public synchronized PluginRecord enable(String pluginId) {
        PluginRecord record = get(pluginId);
        if (!running.containsKey(pluginId)) {
            try {
                start(record);
            } catch (RuntimeException e) {
                record.setLastError(message(e));
                records.save(record);
                throw ApiException.unprocessable("PLUGIN_START_FAILED", "The plugin failed to start: " + message(e));
            }
        }
        record.setEnabled(true);
        record.setLastError(null);
        return records.save(record);
    }

    public synchronized PluginRecord disable(String pluginId) {
        PluginRecord record = get(pluginId);
        stop(pluginId);
        record.setEnabled(false);
        return records.save(record);
    }

    public synchronized void uninstall(String pluginId) {
        PluginRecord record = get(pluginId);
        stop(pluginId);
        records.delete(record);
        try {
            Files.deleteIfExists(Path.of(record.getJarPath()));
        } catch (IOException e) {
            log.warn("Could not delete {}", record.getJarPath(), e);
        }
    }

    public PluginSettings settings(String pluginId) {
        return new FilePluginSettings(dataDir(pluginId).resolve("settings.properties"));
    }

    private void start(PluginRecord record) {
        URLClassLoader loader = null;
        try {
            loader = new URLClassLoader("plugin-" + record.getId(), new URL[] { Path.of(record.getJarPath()).toUri().toURL() },
                    getClass().getClassLoader());
            Class<?> type = Class.forName(record.getMainClass(), true, loader);
            if (!OpenBasedPlugin.class.isAssignableFrom(type)) {
                throw new IllegalStateException(record.getMainClass() + " does not implement OpenBasedPlugin");
            }
            OpenBasedPlugin plugin = (OpenBasedPlugin) type.getDeclaredConstructor().newInstance();
            Runtime runtime = new Runtime(plugin, loader);
            running.put(record.getId(), runtime);
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                plugin.start(new Context(record.getId(), runtime));
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
            log.info("Started plugin {} {}", record.getId(), record.getVersion());
        } catch (Exception | LinkageError e) {
            stop(record.getId());
            if (loader != null && !running.containsKey(record.getId())) {
                closeQuietly(loader);
            }
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e.getMessage(), e);
        }
    }

    private void stop(String pluginId) {
        Runtime runtime = running.remove(pluginId);
        if (runtime == null) {
            return;
        }
        try {
            runtime.plugin.stop();
        } catch (Exception | LinkageError e) {
            log.warn("Plugin {} failed to stop cleanly", pluginId, e);
        }
        runtime.cleanups.forEach(Runnable::run);
        registry.removeOwner(pluginId);
        closeQuietly(runtime.classLoader);
        log.info("Stopped plugin {}", pluginId);
    }

    private static void closeQuietly(URLClassLoader loader) {
        try {
            loader.close();
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    private Path pluginDir() {
        return properties.getDataDir().resolve("plugins");
    }

    private Path dataDir(String pluginId) {
        return pluginDir().resolve("data").resolve(pluginId);
    }

    private static String message(Throwable e) {
        String m = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
        return m.length() > 1000 ? m.substring(0, 1000) : m;
    }

    @PreDestroy
    void shutdown() {
        List.copyOf(running.keySet()).forEach(this::stop);
        scheduler.shutdownNow();
    }

    private final class Context implements PluginContext {
        private final String pluginId;
        private final Runtime runtime;
        private final Logger logger;

        Context(String pluginId, Runtime runtime) {
            this.pluginId = pluginId;
            this.runtime = runtime;
            this.logger = LoggerFactory.getLogger("plugin." + pluginId);
        }

        @Override
        public String pluginId() {
            return pluginId;
        }

        @Override
        public Path dataDirectory() {
            Path dir = dataDir(pluginId);
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return dir;
        }

        @Override
        public PluginSettings settings() {
            return PluginManager.this.settings(pluginId);
        }

        @Override
        public Logger logger() {
            return logger;
        }

        @Override
        public void registerMetadataProvider(MetadataProvider provider) {
            registry.addMetadataProvider(pluginId, provider);
        }

        @Override
        public void registerMediaProcessor(MediaProcessor processor) {
            registry.addMediaProcessor(pluginId, processor);
        }

        @Override
        public void registerEventListener(EventListener listener) {
            Runnable unsubscribe = events.subscribe((Event e) -> listener.onEvent(e.type().name(), e.timestamp(), e.data()));
            runtime.cleanups.add(unsubscribe);
        }

        @Override
        public void registerEndpoint(String method, String path, String requiredScope, PluginEndpoint handler) {
            String normalized = path.startsWith("/") ? path.substring(1) : path;
            if (!ENDPOINT_PATH.matcher(normalized).matches() || RESERVED_PATHS.contains(normalized.split("/")[0])) {
                throw new IllegalArgumentException("Invalid or reserved endpoint path: " + path);
            }
            if (requiredScope != null && !Scopes.ALL.contains(requiredScope)) {
                throw new IllegalArgumentException("Unknown scope: " + requiredScope);
            }
            registry.addEndpoint(new PluginRegistry.Endpoint(pluginId, method, normalized, requiredScope, handler));
        }

        @Override
        public void scheduleJob(String name, Duration interval, Runnable task) {
            if (interval.compareTo(Duration.ofSeconds(10)) < 0) {
                throw new IllegalArgumentException("Scheduled jobs must run at most every 10 seconds");
            }
            ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> {
                try {
                    task.run();
                } catch (RuntimeException | LinkageError e) {
                    logger.warn("Scheduled job {} failed", name, e);
                }
            }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
            runtime.cleanups.add(() -> future.cancel(true));
        }
    }

    /** Used by the controller to reject routing to plugins that exist but are not running. */
    public void requireRunning(String pluginId) {
        if (!running.containsKey(pluginId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PLUGIN_NOT_FOUND", "The plugin is not installed or not enabled.");
        }
    }
}
