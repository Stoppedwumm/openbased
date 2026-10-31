package org.openbased.security.oauth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * A persisted OAuth2 authorization (one sign-in of a client). Authorization codes, access, refresh and device
 * codes are stored as SHA-256 hashes ({@link TokenHashes}), so a copy of the database cannot be used to act as
 * a user. ID tokens and user codes are stored as they are.
 */
@Entity
@Table(name = "oauth2_authorizations", indexes = {
        @Index(columnList = "state"),
        @Index(columnList = "authorizationCodeValue"),
        @Index(columnList = "accessTokenValue"),
        @Index(columnList = "refreshTokenValue"),
        @Index(columnList = "userCodeValue"),
        @Index(columnList = "deviceCodeValue"),
        @Index(columnList = "principalName") })
public class AuthorizationEntity {

    static final int TEXT = 100_000;

    @Id
    private String id;
    private String registeredClientId;
    private String principalName;
    private String authorizationGrantType;
    @Column(length = 1000)
    private String authorizedScopes;
    @Column(length = TEXT)
    private String attributes;
    @Column(length = 500)
    private String state;

    private String authorizationCodeValue;
    private Instant authorizationCodeIssuedAt;
    private Instant authorizationCodeExpiresAt;
    @Column(length = TEXT)
    private String authorizationCodeMetadata;

    private String accessTokenValue;
    private Instant accessTokenIssuedAt;
    private Instant accessTokenExpiresAt;
    @Column(length = TEXT)
    private String accessTokenMetadata;
    private String accessTokenType;
    @Column(length = 1000)
    private String accessTokenScopes;

    @Column(length = 8000)
    private String oidcIdTokenValue;
    private Instant oidcIdTokenIssuedAt;
    private Instant oidcIdTokenExpiresAt;
    @Column(length = TEXT)
    private String oidcIdTokenMetadata;

    private String refreshTokenValue;
    private Instant refreshTokenIssuedAt;
    private Instant refreshTokenExpiresAt;
    @Column(length = TEXT)
    private String refreshTokenMetadata;

    private String userCodeValue;
    private Instant userCodeIssuedAt;
    private Instant userCodeExpiresAt;
    @Column(length = TEXT)
    private String userCodeMetadata;

    private String deviceCodeValue;
    private Instant deviceCodeIssuedAt;
    private Instant deviceCodeExpiresAt;
    @Column(length = TEXT)
    private String deviceCodeMetadata;

    /** Latest expiry of any token; the authorization can be deleted after it. */
    private Instant expiresAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRegisteredClientId() { return registeredClientId; }
    public void setRegisteredClientId(String v) { this.registeredClientId = v; }
    public String getPrincipalName() { return principalName; }
    public void setPrincipalName(String v) { this.principalName = v; }
    public String getAuthorizationGrantType() { return authorizationGrantType; }
    public void setAuthorizationGrantType(String v) { this.authorizationGrantType = v; }
    public String getAuthorizedScopes() { return authorizedScopes; }
    public void setAuthorizedScopes(String v) { this.authorizedScopes = v; }
    public String getAttributes() { return attributes; }
    public void setAttributes(String v) { this.attributes = v; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public String getAuthorizationCodeValue() { return authorizationCodeValue; }
    public void setAuthorizationCodeValue(String v) { this.authorizationCodeValue = v; }
    public Instant getAuthorizationCodeIssuedAt() { return authorizationCodeIssuedAt; }
    public void setAuthorizationCodeIssuedAt(Instant v) { this.authorizationCodeIssuedAt = v; }
    public Instant getAuthorizationCodeExpiresAt() { return authorizationCodeExpiresAt; }
    public void setAuthorizationCodeExpiresAt(Instant v) { this.authorizationCodeExpiresAt = v; }
    public String getAuthorizationCodeMetadata() { return authorizationCodeMetadata; }
    public void setAuthorizationCodeMetadata(String v) { this.authorizationCodeMetadata = v; }
    public String getAccessTokenValue() { return accessTokenValue; }
    public void setAccessTokenValue(String v) { this.accessTokenValue = v; }
    public Instant getAccessTokenIssuedAt() { return accessTokenIssuedAt; }
    public void setAccessTokenIssuedAt(Instant v) { this.accessTokenIssuedAt = v; }
    public Instant getAccessTokenExpiresAt() { return accessTokenExpiresAt; }
    public void setAccessTokenExpiresAt(Instant v) { this.accessTokenExpiresAt = v; }
    public String getAccessTokenMetadata() { return accessTokenMetadata; }
    public void setAccessTokenMetadata(String v) { this.accessTokenMetadata = v; }
    public String getAccessTokenType() { return accessTokenType; }
    public void setAccessTokenType(String v) { this.accessTokenType = v; }
    public String getAccessTokenScopes() { return accessTokenScopes; }
    public void setAccessTokenScopes(String v) { this.accessTokenScopes = v; }
    public String getOidcIdTokenValue() { return oidcIdTokenValue; }
    public void setOidcIdTokenValue(String v) { this.oidcIdTokenValue = v; }
    public Instant getOidcIdTokenIssuedAt() { return oidcIdTokenIssuedAt; }
    public void setOidcIdTokenIssuedAt(Instant v) { this.oidcIdTokenIssuedAt = v; }
    public Instant getOidcIdTokenExpiresAt() { return oidcIdTokenExpiresAt; }
    public void setOidcIdTokenExpiresAt(Instant v) { this.oidcIdTokenExpiresAt = v; }
    public String getOidcIdTokenMetadata() { return oidcIdTokenMetadata; }
    public void setOidcIdTokenMetadata(String v) { this.oidcIdTokenMetadata = v; }
    public String getRefreshTokenValue() { return refreshTokenValue; }
    public void setRefreshTokenValue(String v) { this.refreshTokenValue = v; }
    public Instant getRefreshTokenIssuedAt() { return refreshTokenIssuedAt; }
    public void setRefreshTokenIssuedAt(Instant v) { this.refreshTokenIssuedAt = v; }
    public Instant getRefreshTokenExpiresAt() { return refreshTokenExpiresAt; }
    public void setRefreshTokenExpiresAt(Instant v) { this.refreshTokenExpiresAt = v; }
    public String getRefreshTokenMetadata() { return refreshTokenMetadata; }
    public void setRefreshTokenMetadata(String v) { this.refreshTokenMetadata = v; }
    public String getUserCodeValue() { return userCodeValue; }
    public void setUserCodeValue(String v) { this.userCodeValue = v; }
    public Instant getUserCodeIssuedAt() { return userCodeIssuedAt; }
    public void setUserCodeIssuedAt(Instant v) { this.userCodeIssuedAt = v; }
    public Instant getUserCodeExpiresAt() { return userCodeExpiresAt; }
    public void setUserCodeExpiresAt(Instant v) { this.userCodeExpiresAt = v; }
    public String getUserCodeMetadata() { return userCodeMetadata; }
    public void setUserCodeMetadata(String v) { this.userCodeMetadata = v; }
    public String getDeviceCodeValue() { return deviceCodeValue; }
    public void setDeviceCodeValue(String v) { this.deviceCodeValue = v; }
    public Instant getDeviceCodeIssuedAt() { return deviceCodeIssuedAt; }
    public void setDeviceCodeIssuedAt(Instant v) { this.deviceCodeIssuedAt = v; }
    public Instant getDeviceCodeExpiresAt() { return deviceCodeExpiresAt; }
    public void setDeviceCodeExpiresAt(Instant v) { this.deviceCodeExpiresAt = v; }
    public String getDeviceCodeMetadata() { return deviceCodeMetadata; }
    public void setDeviceCodeMetadata(String v) { this.deviceCodeMetadata = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
