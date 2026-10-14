package org.openbased.sync;

import java.time.Instant;
import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface MediaTombstoneRepository extends JpaRepository<MediaTombstone, String> {

    Page<MediaTombstone> findByLibraryIdInAndRemovedAtGreaterThanEqual(Collection<String> libraryIds, Instant since,
            Pageable pageable);

    @Modifying
    @Transactional
    long deleteByRemovedAtBefore(Instant cutoff);
}
