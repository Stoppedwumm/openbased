package org.openbased.security.oauth;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface UsedRefreshTokenRepository extends JpaRepository<UsedRefreshToken, String> {

    @Modifying
    @Transactional
    @Query("delete from UsedRefreshToken t where t.authorizationId = :authorizationId")
    void deleteByAuthorizationId(String authorizationId);

    @Modifying
    @Transactional
    @Query("delete from UsedRefreshToken t where t.expiresAt < :before")
    int deleteExpired(Instant before);
}
