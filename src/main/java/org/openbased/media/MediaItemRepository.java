package org.openbased.media;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface MediaItemRepository extends JpaRepository<MediaItem, String>, JpaSpecificationExecutor<MediaItem> {

    List<MediaItem> findByLibraryId(String libraryId);
}
