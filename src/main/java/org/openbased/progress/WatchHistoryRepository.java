package org.openbased.progress;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface WatchHistoryRepository extends JpaRepository<WatchHistoryEntry, String> {

    Optional<WatchHistoryEntry> findFirstByUserIdAndMediaIdOrderByWatchedAtDesc(String userId, String mediaId);

    @Query("""
            select h from WatchHistoryEntry h
            where h.userId = :userId
              and h.mediaId in (select m.id from MediaItem m where m.libraryId in :libraryIds)
            """)
    Page<WatchHistoryEntry> findVisible(String userId, Collection<String> libraryIds, Pageable pageable);

    @Modifying
    void deleteByMediaId(String mediaId);
}
