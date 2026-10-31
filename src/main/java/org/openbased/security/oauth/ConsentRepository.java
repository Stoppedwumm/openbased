package org.openbased.security.oauth;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentRepository extends JpaRepository<ConsentEntity, ConsentEntity.Key> {
}
