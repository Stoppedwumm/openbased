package org.openbased.security.oauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;

/**
 * Runs before the refresh-token grant. A refresh token that was already rotated away can only be presented
 * again if it was copied, by an attacker or by a client that lost the newer one. Either way the whole sign-in
 * is revoked: the legitimate client signs in again, and a stolen token family becomes useless.
 */
public class RefreshTokenReuseDetector implements AuthenticationProvider {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenReuseDetector.class);

    private final UsedRefreshTokenRepository usedRefreshTokens;
    private final JpaAuthorizationService authorizations;

    public RefreshTokenReuseDetector(UsedRefreshTokenRepository usedRefreshTokens,
            JpaAuthorizationService authorizations) {
        this.usedRefreshTokens = usedRefreshTokens;
        this.authorizations = authorizations;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String token = ((OAuth2RefreshTokenAuthenticationToken) authentication).getRefreshToken();
        usedRefreshTokens.findById(TokenHashes.hash(token)).ifPresent(used -> {
            log.warn("A rotated refresh token of authorization {} was used again; revoking the authorization",
                    used.getAuthorizationId());
            authorizations.removeById(used.getAuthorizationId());
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT,
                    "The refresh token was already used. Sign in again.", null));
        });
        return null;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2RefreshTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
