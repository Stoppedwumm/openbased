package org.openbased.security.oauth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface AuthorizationRepository extends JpaRepository<AuthorizationEntity, String> {

    Optional<AuthorizationEntity> findByState(String state);

    Optional<AuthorizationEntity> findByAuthorizationCodeValue(String value);

    Optional<AuthorizationEntity> findByAccessTokenValue(String value);

    Optional<AuthorizationEntity> findByRefreshTokenValue(String value);

    Optional<AuthorizationEntity> findByOidcIdTokenValue(String value);

    Optional<AuthorizationEntity> findByUserCodeValue(String value);

    Optional<AuthorizationEntity> findByDeviceCodeValue(String value);

    List<AuthorizationEntity> findByPrincipalName(String principalName);

    @Modifying
    @Transactional
    @Query("delete from AuthorizationEntity a where a.expiresAt < :before")
    int deleteExpired(Instant before);
}
