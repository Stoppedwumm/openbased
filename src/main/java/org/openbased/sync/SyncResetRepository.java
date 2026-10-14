package org.openbased.sync;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface SyncResetRepository extends JpaRepository<SyncReset, String> {

    @Query("""
            select count(r) > 0 from SyncReset r
            where r.createdAt >= :since and (r.userId is null or r.userId = :userId)
            """)
    boolean existsSince(String userId, Instant since);

    @Modifying
    @Transactional
    long deleteByCreatedAtBefore(Instant cutoff);
}
