package org.openbased.token;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface ApiTokenRepository extends JpaRepository<ApiToken, String> {

    Optional<ApiToken> findByTokenHash(String tokenHash);

    List<ApiToken> findByUserIdOrderByCreatedAtDesc(String userId);

    @Modifying
    @Transactional
    @Query("update ApiToken t set t.lastUsedAt = :at where t.id = :id")
    void touch(String id, Instant at);
}
