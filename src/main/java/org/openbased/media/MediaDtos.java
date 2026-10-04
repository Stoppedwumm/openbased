package org.openbased.media;

import java.util.List;
import java.util.Map;

/** Response bodies for media endpoints. */
public final class MediaDtos {

    private MediaDtos() {
    }

    public static String artworkUrl(String artworkId) {
        return artworkId == null ? null : "/api/v1/artwork/" + artworkId;
    }

    /** The current user's playback state; positions and durations in seconds. */
    public record UserProgress(double position, Double duration, boolean completed) {
    }

    /**
     * @param progress the caller's progress, present only for tokens with {@code history.read} and only
     *        once the item has been played
     */
    public record MediaSummary(String id, String libraryId, MediaType type, String title, Integer year, Long duration,
            Integer runtime, String overview, List<String> genres, String poster, String backdrop, String seriesTitle,
            Integer seasonNumber, Integer episodeNumber, UserProgress progress) {

        public static MediaSummary of(MediaItem m) {
            return of(m, null);
        }

        public static MediaSummary of(MediaItem m, UserProgress progress) {
            return new MediaSummary(m.getId(), m.getLibraryId(), m.getType(), m.getTitle(), m.getYear(),
                    m.getDuration(), m.getRuntime(), m.getOverview(), List.copyOf(m.getGenres()),
                    artworkUrl(m.getPosterId()), artworkUrl(m.getBackdropId()), m.getSeriesTitle(),
                    m.getSeasonNumber(), m.getEpisodeNumber(), progress);
        }
    }

    public record ArtworkLinks(String poster, String backdrop) {
    }

    public record FileInfo(String id, String container, long size, Long duration, String videoCodec,
            String audioCodec, Integer width, Integer height) {

        public static FileInfo of(MediaFile f) {
            return new FileInfo(f.getId(), f.getContainer(), f.getSize(), f.getDuration(), f.getVideoCodec(),
                    f.getAudioCodec(), f.getWidth(), f.getHeight());
        }
    }

    public record MediaDetail(String id, String libraryId, MediaType type, String title, String originalTitle,
            Integer year, String overview, List<String> genres, Integer runtime, Long duration,
            Map<String, String> externalIds, ArtworkLinks artwork, String seriesTitle, Integer seasonNumber,
            Integer episodeNumber, List<FileInfo> files, UserProgress progress) {

        public static MediaDetail of(MediaItem m, List<MediaFile> files, UserProgress progress) {
            return new MediaDetail(m.getId(), m.getLibraryId(), m.getType(), m.getTitle(), m.getOriginalTitle(),
                    m.getYear(), m.getOverview(), List.copyOf(m.getGenres()), m.getRuntime(), m.getDuration(),
                    Map.copyOf(m.getExternalIds()),
                    new ArtworkLinks(artworkUrl(m.getPosterId()), artworkUrl(m.getBackdropId())),
                    m.getSeriesTitle(), m.getSeasonNumber(), m.getEpisodeNumber(),
                    files.stream().map(FileInfo::of).toList(), progress);
        }
    }

    public record SearchResults(List<MediaSummary> results, int page, int pageSize, long total) {
    }
}
