package org.openbased.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.openbased.event.EventBus;
import org.openbased.event.EventType;
import org.openbased.progress.PlaybackProgressRepository;
import org.openbased.progress.WatchHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    private final MediaItemRepository items;
    private final MediaFileRepository files;
    private final ArtworkRepository artwork;
    private final PlaybackProgressRepository progress;
    private final WatchHistoryRepository history;
    private final EventBus events;

    public MediaService(MediaItemRepository items, MediaFileRepository files, ArtworkRepository artwork,
            PlaybackProgressRepository progress, WatchHistoryRepository history, EventBus events) {
        this.items = items;
        this.files = files;
        this.artwork = artwork;
        this.progress = progress;
        this.history = history;
        this.events = events;
    }

    /**
     * Removes a media item and everything attached to it from the database.
     *
     * @param deleteFiles also delete the media files from disk
     */
    @Transactional
    public void remove(MediaItem item, boolean deleteFiles) {
        for (MediaFile file : files.findByMediaIdOrderByIdAsc(item.getId())) {
            if (deleteFiles) {
                deleteQuietly(Path.of(file.getPath()));
            }
            files.delete(file);
        }
        for (Artwork art : artwork.findByMediaId(item.getId())) {
            if (art.isManaged()) {
                deleteQuietly(Path.of(art.getPath()));
            }
            artwork.delete(art);
        }
        progress.deleteByMediaId(item.getId());
        history.deleteByMediaId(item.getId());
        items.delete(item);
        events.publishForLibrary(EventType.MEDIA_REMOVED, item.getLibraryId(), Map.of("mediaId", item.getId()));
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete {}", path, e);
        }
    }
}
