package org.openbased.security.oauth;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores the scopes users consented to per client, for clients that ask for consent. */
@Service
public class JpaConsentService implements OAuth2AuthorizationConsentService {

    private final ConsentRepository consents;

    public JpaConsentService(ConsentRepository consents) {
        this.consents = consents;
    }

    @Override
    @Transactional
    public void save(OAuth2AuthorizationConsent consent) {
        ConsentEntity entity = new ConsentEntity();
        entity.setRegisteredClientId(consent.getRegisteredClientId());
        entity.setPrincipalName(consent.getPrincipalName());
        entity.setAuthorities(consent.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(",")));
        consents.save(entity);
    }

    @Override
    @Transactional
    public void remove(OAuth2AuthorizationConsent consent) {
        consents.deleteById(new ConsentEntity.Key(consent.getRegisteredClientId(), consent.getPrincipalName()));
    }

    @Override
    @Transactional(readOnly = true)
    public OAuth2AuthorizationConsent findById(String registeredClientId, String principalName) {
        return consents.findById(new ConsentEntity.Key(registeredClientId, principalName))
                .map(entity -> {
                    OAuth2AuthorizationConsent.Builder builder = OAuth2AuthorizationConsent.withId(
                            entity.getRegisteredClientId(), entity.getPrincipalName());
                    Arrays.stream(entity.getAuthorities().split(",")).filter(a -> !a.isBlank())
                            .forEach(a -> builder.authority(new SimpleGrantedAuthority(a)));
                    return builder.build();
                })
                .orElse(null);
    }
}
