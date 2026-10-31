package org.openbased.metadata;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.openbased.common.ApiException;
import org.openbased.common.Ids;
import org.openbased.config.OpenBasedProperties;
import org.openbased.event.EventBus;
import org.openbased.event.EventType;
import org.openbased.media.Artwork;
import org.openbased.media.ArtworkRepository;
import org.openbased.media.MediaItem;
import org.openbased.media.MediaItemRepository;
import org.openbased.plugin.PluginRegistry;
import org.openbased.plugin.api.MetadataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class MetadataService {

    private static final Logger log = LoggerFactory.getLogger(MetadataService.class);
    private static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;

    private final PluginRegistry registry;
    private final MediaItemRepository items;
    private final ArtworkRepository artwork;
    private final EventBus events;
    private final OpenBasedProperties properties;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public MetadataService(PluginRegistry registry, MediaItemRepository items, ArtworkRepository artwork,
            EventBus events, OpenBasedProperties properties, ObjectMapper objectMapper) {
        this.registry = registry;
        this.items = items;
        this.artwork = artwork;
        this.events = events;
        this.properties = properties;
        String tmdbKey = properties.getMetadata().getTmdb().getApiKey();
        if (tmdbKey != null && !tmdbKey.isBlank()) {
            registry.addMetadataProvider("core", new TmdbMetadataProvider(properties.getMetadata().getTmdb(), objectMapper));
        }
    }

    public record SearchResponse(String provider, List<MetadataProvider.Result> results) {
    }

    /** The named provider, or the configured default, or the first available one. */
    public MetadataProvider provider(String id) {
        if (id != null) {
            return registry.metadataProvider(id).orElseThrow(() -> ApiException.notFound("PROVIDER_NOT_FOUND",
                    "No metadata provider named " + id + " is configured."));
        }
        return defaultProvider().orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "NO_METADATA_PROVIDER", "No metadata provider is configured."));
    }

    private Optional<MetadataProvider> defaultProvider() {
        return registry.metadataProvider(properties.getMetadata().getDefaultProvider())
                .or(() -> registry.metadataProviders().stream().findFirst());
    }

    public SearchResponse search(String providerId, String type, String query, Integer year) {
        MetadataProvider provider = provider(providerId);
        try {
            return new SearchResponse(provider.id(), provider.search(type, query, year));
        } catch (Exception e) {
            log.warn("Metadata search failed with provider {}", provider.id(), e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "METADATA_PROVIDER_FAILED",
                    "The metadata provider " + provider.id() + " is unavailable.");
        }
    }

    /** Matches a newly scanned item if automatic matching is enabled; failures are logged, not thrown. */
    public void autoMatch(String mediaId) {
        if (!properties.getMetadata().isAutoMatch() || defaultProvider().isEmpty()) {
            return;
        }
        try {
            refresh(mediaId, null);
        } catch (Exception e) {
            log.info("Automatic metadata match failed for {}: {}", mediaId, e.getMessage());
        }
    }

    /**
     * Refreshes an item's metadata. Uses the stored external ID when present; otherwise searches by title
     * and year and takes the best match.
     *
     * @return whether metadata was found
     */
    public boolean refresh(String mediaId, String providerId) throws Exception {
        MediaItem item = items.findById(mediaId).orElseThrow();
        MetadataProvider provider = providerId != null ? provider(providerId) : defaultProvider().orElseThrow(
                () -> new IllegalStateException("No metadata provider is configured"));
        String type = item.getType().name();
        String externalId = item.getExternalIds().get(provider.id());
        if (externalId == null) {
            String query = item.getSeriesTitle() != null ? item.getSeriesTitle() : item.getTitle();
            Integer year = item.getSeriesTitle() != null ? null : item.getYear();
            List<MetadataProvider.Result> results = provider.search(type, query, year);
            if (results.isEmpty() && year != null) {
                results = provider.search(type, query, null);
            }
            if (results.isEmpty()) {
                return false;
            }
            externalId = results.get(0).externalId();
        }
        Optional<MetadataProvider.Details> details = provider.fetch(type, externalId);
        if (details.isEmpty()) {
            return false;
        }
        apply(item.getId(), provider.id(), details.get());
        return true;
    }

    private void apply(String mediaId, String providerId, MetadataProvider.Details d) {
        String posterId = download(mediaId, "POSTER", d.posterUrl(), d.httpHeaders());
        String backdropId = download(mediaId, "BACKDROP", d.backdropUrl(), d.httpHeaders());
        MediaItem item = items.findById(mediaId).orElse(null);
        if (item == null) {
            return;
        }
        item.getExternalIds().put(providerId, d.externalId());
        if (item.getSeriesTitle() != null) {
            // Episodes keep their own title; series metadata fills the gaps.
            if (d.title() != null) {
                item.setSeriesTitle(d.title());
            }
        } else {
            if (d.title() != null) {
                item.setTitle(d.title());
            }
            if (d.year() != null) {
                item.setYear(d.year());
            }
            if (d.runtime() != null) {
                item.setRuntime(d.runtime());
            }
        }
        if (d.originalTitle() != null) {
            item.setOriginalTitle(d.originalTitle());
        }
        if (d.overview() != null) {
            item.setOverview(d.overview());
        }
        if (d.genres() != null && !d.genres().isEmpty()) {
            item.setGenres(new ArrayList<>(d.genres()));
        }
        if (posterId != null) {
            replaceArtwork(item.getPosterId());
            item.setPosterId(posterId);
        }
        if (backdropId != null) {
            replaceArtwork(item.getBackdropId());
            item.setBackdropId(backdropId);
        }
        item.setUpdatedAt(Instant.now());
        items.save(item);
        events.publishForLibrary(EventType.MEDIA_UPDATED, item.getLibraryId(), Map.of("mediaId", item.getId()));
    }

    private void replaceArtwork(String artworkId) {
        if (artworkId == null) {
            return;
        }
        artwork.findById(artworkId).filter(Artwork::isManaged).ifPresent(old -> {
            try {
                Files.deleteIfExists(Path.of(old.getPath()));
            } catch (IOException e) {
                log.debug("Could not delete {}", old.getPath(), e);
            }
            artwork.delete(old);
        });
    }

    private String download(String mediaId, String kind, String url, Map<String, String> headers) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            return null;
        }
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
            if (headers != null) {
                headers.forEach(request::header);
            }
            HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            String contentType = response.headers().firstValue("Content-Type").orElse("image/jpeg");
            if (response.statusCode() / 100 != 2 || !contentType.startsWith("image/")) {
                response.body().close();
                return null;
            }
            String id = Ids.prefixed("art");
            Path dir = properties.getDataDir().resolve("artwork");
            Files.createDirectories(dir);
            Path target = dir.resolve(id);
            try (InputStream in = response.body()) {
                Files.copy(new LimitedInputStream(in, MAX_IMAGE_BYTES), target, StandardCopyOption.REPLACE_EXISTING);
            }
            Artwork art = new Artwork();
            art.setId(id);
            art.setMediaId(mediaId);
            art.setKind(kind);
            art.setPath(target.toAbsolutePath().toString());
            art.setContentType(contentType.split(";")[0].trim());
            art.setManaged(true);
            artwork.save(art);
            return id;
        } catch (IOException e) {
            log.info("Could not download {} for {}: {}", kind, mediaId, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Fails once more than {@code limit} bytes have been read. */
    private static final class LimitedInputStream extends java.io.FilterInputStream {
        private long remaining;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && --remaining < 0) {
                throw new IOException("Image too large");
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int n = super.read(buffer, off, len);
            if (n > 0 && (remaining -= n) < 0) {
                throw new IOException("Image too large");
            }
            return n;
        }
    }
}
