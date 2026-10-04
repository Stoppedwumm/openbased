package org.openbased.media;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaFileRepository extends JpaRepository<MediaFile, String> {

    List<MediaFile> findByMediaIdOrderByIdAsc(String mediaId);

    List<MediaFile> findByLibraryId(String libraryId);

    Optional<MediaFile> findByPath(String path);

    long countByMediaId(String mediaId);
}
