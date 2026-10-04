package org.openbased.progress;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PlaybackProgressRepository extends JpaRepository<PlaybackProgress, String> {

    Optional<PlaybackProgress> findByUserIdAndMediaId(String userId, String mediaId);

    @Query("""
            select p from PlaybackProgress p
            where p.userId = :userId and p.completed = false and p.position > 0
              and p.mediaId in (select m.id from MediaItem m where m.libraryId in :libraryIds)
            """)
    Page<PlaybackProgress> findInProgress(String userId, Collection<String> libraryIds, Pageable pageable);

    @Modifying
    void deleteByMediaId(String mediaId);
}
