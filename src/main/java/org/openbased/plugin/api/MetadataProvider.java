package org.openbased.plugin.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Looks up descriptive metadata for media. */
public interface MetadataProvider {

    /** Short identifier, also used as the key in a media item's {@code externalIds}. */
    String id();

    /**
     * @param type {@code MOVIE}, {@code EPISODE}, {@code TRACK} or {@code VIDEO}
     */
    List<Result> search(String type, String query, Integer year) throws Exception;

    Optional<Details> fetch(String type, String externalId) throws Exception;

    record Result(String externalId, String title, Integer year, String poster) {
    }

    /**
     * @param runtime runtime in minutes
     * @param posterUrl absolute URL of a poster image, if any
     * @param backdropUrl absolute URL of a backdrop image, if any
     * @param httpHeaders headers needed to download the images, if any
     */
    record Details(String externalId, String title, String originalTitle, Integer year, String overview,
            List<String> genres, Integer runtime, String posterUrl, String backdropUrl,
            Map<String, String> httpHeaders) {
    }
}
