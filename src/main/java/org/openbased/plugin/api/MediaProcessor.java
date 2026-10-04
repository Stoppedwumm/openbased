package org.openbased.plugin.api;

import java.nio.file.Path;
import java.util.List;

/** Invoked after the scanner adds a new media item. */
public interface MediaProcessor {

    void process(MediaInfo media) throws Exception;

    record MediaInfo(String id, String libraryId, String type, String title, Integer year, List<Path> files) {
    }
}
