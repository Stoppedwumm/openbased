package org.openbased.security.oauth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.security.oauth2.core.AbstractOAuth2Token;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2DeviceCode;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2UserCode;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationServerJackson2Module;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores OAuth2 authorizations in the database, so sign-ins and refresh tokens survive restarts.
 *
 * <p>Secret token values are stored hashed. A lookup by token hashes the presented value; on a match the raw
 * value is put back into the returned authorization, because Spring Authorization Server finds the matching
 * token in an authorization by its value (revocation, introspection).
 *
 * <p>When a refresh token is replaced by rotation, its hash is remembered in {@link UsedRefreshToken} so that
 * a later attempt to use it can be detected by {@link RefreshTokenReuseDetector}.
 */
@Service
public class JpaAuthorizationService implements OAuth2AuthorizationService {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final OAuth2TokenType STATE = new OAuth2TokenType(OAuth2ParameterNames.STATE);
    private static final OAuth2TokenType CODE = new OAuth2TokenType(OAuth2ParameterNames.CODE);
    private static final OAuth2TokenType ID_TOKEN = new OAuth2TokenType(OidcParameterNames.ID_TOKEN);
    private static final OAuth2TokenType USER_CODE = new OAuth2TokenType(OAuth2ParameterNames.USER_CODE);
    private static final OAuth2TokenType DEVICE_CODE = new OAuth2TokenType(OAuth2ParameterNames.DEVICE_CODE);

    private final AuthorizationRepository authorizations;
    private final UsedRefreshTokenRepository usedRefreshTokens;
    private final RegisteredClientRepository clients;
    private final ObjectMapper json = new ObjectMapper();

    public JpaAuthorizationService(AuthorizationRepository authorizations, UsedRefreshTokenRepository usedRefreshTokens,
            RegisteredClientRepository clients) {
        this.authorizations = authorizations;
        this.usedRefreshTokens = usedRefreshTokens;
        this.clients = clients;
        ClassLoader classLoader = JpaAuthorizationService.class.getClassLoader();
        json.registerModules(SecurityJackson2Modules.getModules(classLoader));
        json.registerModule(new OAuth2AuthorizationServerJackson2Module());
    }

    @Override
    @Transactional
    public void save(OAuth2Authorization authorization) {
        AuthorizationEntity entity = toEntity(authorization);
        authorizations.findById(entity.getId()).ifPresent(previous -> {
            String old = previous.getRefreshTokenValue();
            if (old != null && !old.equals(entity.getRefreshTokenValue()) && !usedRefreshTokens.existsById(old)) {
                usedRefreshTokens.save(new UsedRefreshToken(old, entity.getId(), Instant.now(),
                        previous.getRefreshTokenExpiresAt()));
            }
        });
        authorizations.save(entity);
    }

    @Override
    @Transactional
    public void remove(OAuth2Authorization authorization) {
        removeById(authorization.getId());
    }

    /** Removes an authorization and everything remembered about it. */
    @Transactional
    public void removeById(String authorizationId) {
        authorizations.deleteById(authorizationId);
        usedRefreshTokens.deleteByAuthorizationId(authorizationId);
    }

    @Override
    @Transactional(readOnly = true)
    public OAuth2Authorization findById(String id) {
        return authorizations.findById(id).map(e -> toObject(e, null)).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        if (token == null) {
            return null;
        }
        String hash = TokenHashes.hash(token);
        Optional<AuthorizationEntity> result;
        if (tokenType == null) {
            result = authorizations.findByState(token)
                    .or(() -> authorizations.findByAuthorizationCodeValue(hash))
                    .or(() -> authorizations.findByAccessTokenValue(hash))
                    .or(() -> authorizations.findByRefreshTokenValue(hash))
                    .or(() -> authorizations.findByOidcIdTokenValue(token))
                    .or(() -> authorizations.findByUserCodeValue(token))
                    .or(() -> authorizations.findByDeviceCodeValue(hash));
        } else if (STATE.equals(tokenType)) {
            result = authorizations.findByState(token);
        } else if (CODE.equals(tokenType)) {
            result = authorizations.findByAuthorizationCodeValue(hash);
        } else if (OAuth2TokenType.ACCESS_TOKEN.equals(tokenType)) {
            result = authorizations.findByAccessTokenValue(hash);
        } else if (OAuth2TokenType.REFRESH_TOKEN.equals(tokenType)) {
            result = authorizations.findByRefreshTokenValue(hash);
        } else if (ID_TOKEN.equals(tokenType)) {
            result = authorizations.findByOidcIdTokenValue(token);
        } else if (USER_CODE.equals(tokenType)) {
            result = authorizations.findByUserCodeValue(token);
        } else if (DEVICE_CODE.equals(tokenType)) {
            result = authorizations.findByDeviceCodeValue(hash);
        } else {
            result = Optional.empty();
        }
        return result.map(e -> toObject(e, token)).orElse(null);
    }

    /**
     * Whether an access token belongs to a sign-in that still exists and has not been revoked. This runs on every
     * API request, so it reads only the access token's columns.
     */
    @Transactional(readOnly = true)
    public boolean isAccessTokenActive(String accessToken) {
        return authorizations.findByAccessTokenValue(TokenHashes.hash(accessToken))
                .filter(e -> e.getAccessTokenExpiresAt() == null || e.getAccessTokenExpiresAt().isAfter(Instant.now()))
                .map(e -> !Boolean.TRUE.equals(parse(e.getAccessTokenMetadata())
                        .get(OAuth2Authorization.Token.INVALIDATED_METADATA_NAME)))
                .orElse(false);
    }

    /** Authorizations of a user, for listing connected apps. */
    @Transactional(readOnly = true)
    public List<OAuth2Authorization> findByPrincipalName(String principalName) {
        return authorizations.findByPrincipalName(principalName).stream().map(e -> toObject(e, null)).toList();
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
    void deleteExpired() {
        Instant now = Instant.now();
        authorizations.deleteExpired(now);
        usedRefreshTokens.deleteExpired(now);
    }

    // ------------------------------------------------------------------ mapping

    private OAuth2Authorization toObject(AuthorizationEntity e, String rawToken) {
        RegisteredClient client = clients.findById(e.getRegisteredClientId());
        if (client == null) {
            throw new DataRetrievalFailureException("Registered client " + e.getRegisteredClientId()
                    + " of authorization " + e.getId() + " no longer exists");
        }
        OAuth2Authorization.Builder builder = OAuth2Authorization.withRegisteredClient(client)
                .id(e.getId())
                .principalName(e.getPrincipalName())
                .authorizationGrantType(new AuthorizationGrantType(e.getAuthorizationGrantType()))
                .authorizedScopes(split(e.getAuthorizedScopes()))
                .attributes(attributes -> attributes.putAll(parse(e.getAttributes())));
        if (e.getState() != null) {
            builder.attribute(OAuth2ParameterNames.STATE, e.getState());
        }
        if (e.getAuthorizationCodeValue() != null) {
            builder.token(new OAuth2AuthorizationCode(raw(e.getAuthorizationCodeValue(), rawToken),
                    e.getAuthorizationCodeIssuedAt(), e.getAuthorizationCodeExpiresAt()),
                    metadata(e.getAuthorizationCodeMetadata()));
        }
        if (e.getAccessTokenValue() != null) {
            builder.token(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                    raw(e.getAccessTokenValue(), rawToken), e.getAccessTokenIssuedAt(), e.getAccessTokenExpiresAt(),
                    split(e.getAccessTokenScopes())), metadata(e.getAccessTokenMetadata()));
        }
        if (e.getOidcIdTokenValue() != null) {
            Map<String, Object> metadata = parse(e.getOidcIdTokenMetadata());
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = (Map<String, Object>) metadata.get(OAuth2Authorization.Token.CLAIMS_METADATA_NAME);
            builder.token(new OidcIdToken(e.getOidcIdTokenValue(), e.getOidcIdTokenIssuedAt(),
                    e.getOidcIdTokenExpiresAt(), claims), m -> m.putAll(metadata));
        }
        if (e.getRefreshTokenValue() != null) {
            builder.token(new OAuth2RefreshToken(raw(e.getRefreshTokenValue(), rawToken), e.getRefreshTokenIssuedAt(),
                    e.getRefreshTokenExpiresAt()), metadata(e.getRefreshTokenMetadata()));
        }
        if (e.getUserCodeValue() != null) {
            builder.token(new OAuth2UserCode(e.getUserCodeValue(), e.getUserCodeIssuedAt(), e.getUserCodeExpiresAt()),
                    metadata(e.getUserCodeMetadata()));
        }
        if (e.getDeviceCodeValue() != null) {
            builder.token(new OAuth2DeviceCode(raw(e.getDeviceCodeValue(), rawToken), e.getDeviceCodeIssuedAt(),
                    e.getDeviceCodeExpiresAt()), metadata(e.getDeviceCodeMetadata()));
        }
        return builder.build();
    }

    /** The presented raw token if it is the one stored as {@code stored}, else the stored (hashed) value. */
    private static String raw(String stored, String rawToken) {
        return rawToken != null && stored.equals(TokenHashes.hash(rawToken)) ? rawToken : stored;
    }

    private AuthorizationEntity toEntity(OAuth2Authorization a) {
        AuthorizationEntity e = new AuthorizationEntity();
        e.setId(a.getId());
        e.setRegisteredClientId(a.getRegisteredClientId());
        e.setPrincipalName(a.getPrincipalName());
        e.setAuthorizationGrantType(a.getAuthorizationGrantType().getValue());
        e.setAuthorizedScopes(String.join(",", a.getAuthorizedScopes()));
        e.setAttributes(write(a.getAttributes()));
        e.setState(a.getAttribute(OAuth2ParameterNames.STATE));

        OAuth2Authorization.Token<OAuth2AuthorizationCode> code = a.getToken(OAuth2AuthorizationCode.class);
        set(code, true, e::setAuthorizationCodeValue, e::setAuthorizationCodeIssuedAt,
                e::setAuthorizationCodeExpiresAt, e::setAuthorizationCodeMetadata);
        OAuth2Authorization.Token<OAuth2AccessToken> access = a.getToken(OAuth2AccessToken.class);
        set(access, true, e::setAccessTokenValue, e::setAccessTokenIssuedAt, e::setAccessTokenExpiresAt,
                e::setAccessTokenMetadata);
        if (access != null) {
            e.setAccessTokenType(access.getToken().getTokenType().getValue());
            e.setAccessTokenScopes(String.join(",", access.getToken().getScopes()));
        }
        OAuth2Authorization.Token<OidcIdToken> idToken = a.getToken(OidcIdToken.class);
        set(idToken, false, e::setOidcIdTokenValue, e::setOidcIdTokenIssuedAt, e::setOidcIdTokenExpiresAt,
                e::setOidcIdTokenMetadata);
        OAuth2Authorization.Token<OAuth2RefreshToken> refresh = a.getToken(OAuth2RefreshToken.class);
        set(refresh, true, e::setRefreshTokenValue, e::setRefreshTokenIssuedAt, e::setRefreshTokenExpiresAt,
                e::setRefreshTokenMetadata);
        OAuth2Authorization.Token<OAuth2UserCode> userCode = a.getToken(OAuth2UserCode.class);
        set(userCode, false, e::setUserCodeValue, e::setUserCodeIssuedAt, e::setUserCodeExpiresAt,
                e::setUserCodeMetadata);
        OAuth2Authorization.Token<OAuth2DeviceCode> deviceCode = a.getToken(OAuth2DeviceCode.class);
        set(deviceCode, true, e::setDeviceCodeValue, e::setDeviceCodeIssuedAt, e::setDeviceCodeExpiresAt,
                e::setDeviceCodeMetadata);

        // Keep the authorization until its last token expires; requests still waiting for consent get a day.
        Instant latest = Stream.of(code, access, idToken, refresh, userCode, deviceCode)
                .filter(Objects::nonNull)
                .map(t -> t.getToken().getExpiresAt())
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(Instant.now().plus(1, ChronoUnit.DAYS));
        e.setExpiresAt(latest);
        return e;
    }

    private <T extends AbstractOAuth2Token> void set(OAuth2Authorization.Token<T> token, boolean hashed,
            Consumer<String> value, Consumer<Instant> issuedAt, Consumer<Instant> expiresAt, Consumer<String> metadata) {
        if (token == null) {
            return;
        }
        T t = token.getToken();
        value.accept(hashed ? TokenHashes.hash(t.getTokenValue()) : t.getTokenValue());
        issuedAt.accept(t.getIssuedAt());
        expiresAt.accept(t.getExpiresAt());
        metadata.accept(write(token.getMetadata()));
    }

    private Consumer<Map<String, Object>> metadata(String value) {
        Map<String, Object> parsed = parse(value);
        return m -> m.putAll(parsed);
    }

    private static Set<String> split(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<String, Object> parse(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(value, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unreadable OAuth2 authorization data", e);
        }
    }

    private String write(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("OAuth2 authorization data cannot be stored", e);
        }
    }
}
