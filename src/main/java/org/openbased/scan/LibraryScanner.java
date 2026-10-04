package org.openbased.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.openbased.common.Ids;
import org.openbased.event.EventBus;
import org.openbased.event.EventType;
import org.openbased.job.JobService;
import org.openbased.library.Library;
import org.openbased.library.LibraryRepository;
import org.openbased.library.LibraryType;
import org.openbased.media.Artwork;
import org.openbased.media.ArtworkRepository;
import org.openbased.media.MediaFile;
import org.openbased.media.MediaFileRepository;
import org.openbased.media.MediaItem;
import org.openbased.media.MediaItemRepository;
import org.openbased.media.MediaService;
import org.openbased.media.MediaType;
import org.openbased.metadata.MetadataService;
import org.openbased.plugin.PluginRegistry;
import org.openbased.plugin.api.MediaProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Walks library folders and keeps media items in sync with the files on disk. */
@Service
public class LibraryScanner {

    private static final Logger log = LoggerFactory.getLogger(LibraryScanner.class);

    public static final String JOB_TYPE = "LIBRARY_SCAN";

    private static final Set<String> VIDEO = Set.of("mkv", "mp4", "m4v", "mov", "avi", "webm", "ts", "m2ts", "wmv");
    private static final Set<String> AUDIO = Set.of("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav");
    private static final List<String> POSTER_NAMES = List.of("poster", "folder", "cover");
    private static final List<String> BACKDROP_NAMES = List.of("fanart", "backdrop", "background");
    private static final List<String> IMAGE_EXTENSIONS = List.of("jpg", "jpeg", "png", "webp");

    private final LibraryRepository libraries;
    private final MediaItemRepository items;
    private final MediaFileRepository files;
    private final ArtworkRepository artwork;
    private final MediaService mediaService;
    private final MediaProbe probe;
    private final MetadataService metadata;
    private final PluginRegistry registry;
    private final EventBus events;

    public LibraryScanner(LibraryRepository libraries, MediaItemRepository items, MediaFileRepository files,
            ArtworkRepository artwork, MediaService mediaService, MediaProbe probe, MetadataService metadata,
            PluginRegistry registry, EventBus events) {
        this.libraries = libraries;
        this.items = items;
        this.files = files;
        this.artwork = artwork;
        this.mediaService = mediaService;
        this.probe = probe;
        this.metadata = metadata;
        this.registry = registry;
        this.events = events;
    }

    public static boolean isMediaFile(Path path, LibraryType type) {
        String ext = extension(path);
        return switch (type) {
            case MUSIC -> AUDIO.contains(ext);
            case MOVIES, TV -> VIDEO.contains(ext);
            case OTHER -> VIDEO.contains(ext) || AUDIO.contains(ext);
        };
    }

    public void scan(String libraryId, JobService.Handle handle) throws IOException {
        Library library = libraries.findById(libraryId).orElseThrow(() -> new IllegalStateException("Library was deleted"));
        events.publishForLibrary(EventType.LIBRARY_SCAN_STARTED, libraryId,
                Map.of("libraryId", libraryId, "jobId", handle.jobId()));
        List<Path> found = new ArrayList<>();
        for (String root : library.getPaths()) {
            Path dir = Path.of(root);
            if (!Files.isDirectory(dir)) {
                log.warn("Library {} path {} is not a readable directory", libraryId, root);
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().startsWith("."))
                        .filter(p -> isMediaFile(p, library.getType()))
                        .forEach(found::add);
            }
        }
        Set<String> present = new HashSet<>();
        int done = 0;
        for (Path path : found) {
            if (handle.isCancelled()) {
                return;
            }
            present.add(path.toAbsolutePath().normalize().toString());
            try {
                addOrUpdate(library, path);
            } catch (RuntimeException e) {
                log.warn("Could not scan {}", path, e);
            }
            handle.progress((int) (++done * 95L / Math.max(1, found.size())));
        }
        removeMissing(library, present);
        library.setLastScannedAt(Instant.now());
        libraries.save(library);
        handle.progress(100);
        events.publishForLibrary(EventType.LIBRARY_SCAN_COMPLETED, libraryId,
                Map.of("libraryId", libraryId, "jobId", handle.jobId()));
    }

    /**
     * Adds or refreshes a single file.
     *
     * @return the ID of the media item the file belongs to
     */
    public String addOrUpdate(Library library, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        String key = normalized.toString();
        long size;
        Instant modified;
        try {
            size = Files.size(normalized);
            modified = Files.getLastModifiedTime(normalized).toInstant();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + normalized, e);
        }
        Optional<MediaFile> existing = files.findByPath(key);
        if (existing.isPresent()) {
            MediaFile file = existing.get();
            if (file.getSize() != size || !Objects.equals(file.getModifiedAt(), modified)) {
                applyProbe(file, normalized, size, modified);
                files.save(file);
                items.findById(file.getMediaId()).ifPresent(item -> {
                    item.setDuration(file.getDuration());
                    item.setUpdatedAt(Instant.now());
                    items.save(item);
                    events.publishForLibrary(EventType.MEDIA_UPDATED, library.getId(), Map.of("mediaId", item.getId()));
                });
            }
            return file.getMediaId();
        }

        FilenameParser.Parsed parsed = FilenameParser.parse(normalized, library.getType());
        MediaItem item = findExisting(library, parsed).orElse(null);
        boolean created = item == null;
        if (created) {
            item = new MediaItem();
            item.setId(Ids.prefixed("media"));
            item.setLibraryId(library.getId());
            item.setType(parsed.type());
            item.setTitle(parsed.title());
            item.setYear(parsed.year());
            item.setSeriesTitle(parsed.seriesTitle());
            item.setSeasonNumber(parsed.season());
            item.setEpisodeNumber(parsed.episode());
            item.setAddedAt(Instant.now());
        }
        MediaFile file = new MediaFile();
        file.setId(Ids.prefixed("file"));
        file.setMediaId(item.getId());
        file.setLibraryId(library.getId());
        file.setPath(key);
        file.setContainer(extension(normalized));
        applyProbe(file, normalized, size, modified);
        if (item.getDuration() == null) {
            item.setDuration(file.getDuration());
        }
        item.setUpdatedAt(Instant.now());
        items.save(item);
        files.save(file);
        if (created) {
            attachLocalArtwork(item, normalized);
            items.save(item);
            events.publishForLibrary(EventType.MEDIA_ADDED, library.getId(), Map.of("mediaId", item.getId()));
            metadata.autoMatch(item.getId());
            runProcessors(item, normalized);
        }
        return item.getId();
    }

    private Optional<MediaItem> findExisting(Library library, FilenameParser.Parsed parsed) {
        if (parsed.type() != MediaType.EPISODE && parsed.type() != MediaType.MOVIE) {
            return Optional.empty();
        }
        return items.findByLibraryId(library.getId()).stream()
                .filter(i -> i.getType() == parsed.type())
                .filter(i -> parsed.type() == MediaType.EPISODE
                        ? Objects.equals(i.getSeasonNumber(), parsed.season())
                                && Objects.equals(i.getEpisodeNumber(), parsed.episode())
                                && sameTitle(i.getSeriesTitle(), parsed.seriesTitle())
                        : parsed.year() != null && Objects.equals(i.getYear(), parsed.year())
                                && sameTitle(i.getTitle(), parsed.title()))
                .findFirst();
    }

    private static boolean sameTitle(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private void applyProbe(MediaFile file, Path path, long size, Instant modified) {
        MediaProbe.Info info = probe.probe(path);
        file.setSize(size);
        file.setModifiedAt(modified);
        file.setDuration(info.duration());
        file.setVideoCodec(info.videoCodec());
        file.setAudioCodec(info.audioCodec());
        file.setWidth(info.width());
        file.setHeight(info.height());
    }

    private void removeMissing(Library library, Set<String> present) {
        Set<String> affected = new HashSet<>();
        for (MediaFile file : files.findByLibraryId(library.getId())) {
            if (!present.contains(file.getPath())) {
                files.delete(file);
                affected.add(file.getMediaId());
            }
        }
        for (String mediaId : affected) {
            if (files.countByMediaId(mediaId) == 0) {
                items.findById(mediaId).ifPresent(item -> mediaService.remove(item, false));
            }
        }
    }

    private void attachLocalArtwork(MediaItem item, Path file) {
        String base = FilenameParser.stripExtension(file.getFileName().toString());
        Path dir = file.getParent();
        findImage(dir, base, POSTER_NAMES, item.getType() != MediaType.EPISODE).ifPresent(p ->
                item.setPosterId(saveLocalArtwork(item, "POSTER", p)));
        findImage(dir, base, BACKDROP_NAMES, item.getType() != MediaType.EPISODE).ifPresent(p ->
                item.setBackdropId(saveLocalArtwork(item, "BACKDROP", p)));
    }

    private static Optional<Path> findImage(Path dir, String base, List<String> names, boolean allowGeneric) {
        for (String name : names) {
            for (String ext : IMAGE_EXTENSIONS) {
                Path specific = dir.resolve(base + "-" + name + "." + ext);
                if (Files.isRegularFile(specific)) {
                    return Optional.of(specific);
                }
            }
        }
        if (allowGeneric) {
            for (String name : names) {
                for (String ext : IMAGE_EXTENSIONS) {
                    Path generic = dir.resolve(name + "." + ext);
                    if (Files.isRegularFile(generic)) {
                        return Optional.of(generic);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private String saveLocalArtwork(MediaItem item, String kind, Path path) {
        Artwork art = new Artwork();
        art.setId(Ids.prefixed("art"));
        art.setMediaId(item.getId());
        art.setKind(kind);
        art.setPath(path.toAbsolutePath().toString());
        String ext = extension(path);
        art.setContentType(switch (ext) {
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> "image/jpeg";
        });
        art.setManaged(false);
        artwork.save(art);
        return art.getId();
    }

    private void runProcessors(MediaItem item, Path file) {
        MediaProcessor.MediaInfo info = new MediaProcessor.MediaInfo(item.getId(), item.getLibraryId(),
                item.getType().name(), item.getTitle(), item.getYear(), List.of(file));
        for (PluginRegistry.Owned<MediaProcessor> processor : registry.mediaProcessors()) {
            try {
                processor.value().process(info);
            } catch (Exception e) {
                log.warn("Media processor from {} failed for {}", processor.ownerId(), item.getId(), e);
            }
        }
    }

    static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
