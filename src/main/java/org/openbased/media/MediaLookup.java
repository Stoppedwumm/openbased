package org.openbased.media;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.openbased.common.ApiException;
import org.openbased.security.AccessService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Loads media the current user may access, hiding items in inaccessible libraries. */
@Component
public class MediaLookup {

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("mp4", "video/mp4"), Map.entry("m4v", "video/mp4"), Map.entry("mov", "video/quicktime"),
            Map.entry("mkv", "video/x-matroska"), Map.entry("webm", "video/webm"), Map.entry("avi", "video/x-msvideo"),
            Map.entry("ts", "video/mp2t"), Map.entry("m2ts", "video/mp2t"), Map.entry("wmv", "video/x-ms-wmv"),
            Map.entry("mp3", "audio/mpeg"), Map.entry("flac", "audio/flac"), Map.entry("m4a", "audio/mp4"),
            Map.entry("aac", "audio/aac"), Map.entry("ogg", "audio/ogg"), Map.entry("opus", "audio/ogg"),
            Map.entry("wav", "audio/wav"));

    private final MediaItemRepository items;
    private final MediaFileRepository files;
    private final AccessService access;

    public MediaLookup(MediaItemRepository items, MediaFileRepository files, AccessService access) {
        this.items = items;
        this.files = files;
        this.access = access;
    }

    public MediaItem media(String mediaId) {
        MediaItem item = items.findById(mediaId).orElse(null);
        if (item == null || !access.canAccessLibrary(item.getLibraryId())) {
            throw ApiException.notFound("MEDIA_NOT_FOUND", "The requested media item does not exist.");
        }
        return item;
    }

    /** The requested file of a media item, or its first file. */
    public MediaFile file(MediaItem item, String fileId) {
        List<MediaFile> all = files.findByMediaIdOrderByIdAsc(item.getId());
        MediaFile file = fileId == null ? all.stream().findFirst().orElse(null)
                : all.stream().filter(f -> f.getId().equals(fileId)).findFirst().orElse(null);
        if (file == null) {
            throw ApiException.notFound("FILE_NOT_FOUND", "The media item has no such file.");
        }
        if (!Files.isReadable(Path.of(file.getPath()))) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FILE_UNAVAILABLE",
                    "The media file is currently unavailable.");
        }
        return file;
    }

    public static String contentType(String container) {
        return container == null ? "application/octet-stream"
                : CONTENT_TYPES.getOrDefault(container.toLowerCase(Locale.ROOT), "application/octet-stream");
    }
}
