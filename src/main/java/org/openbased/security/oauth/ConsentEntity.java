package org.openbased.security.oauth;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** Scopes a user has consented to for a client. */
@Entity
@Table(name = "oauth2_authorization_consents")
@IdClass(ConsentEntity.Key.class)
public class ConsentEntity {

    @Id
    private String registeredClientId;
    @Id
    private String principalName;
    @Column(length = 2000)
    private String authorities;

    public String getRegisteredClientId() { return registeredClientId; }
    public void setRegisteredClientId(String v) { this.registeredClientId = v; }
    public String getPrincipalName() { return principalName; }
    public void setPrincipalName(String v) { this.principalName = v; }
    public String getAuthorities() { return authorities; }
    public void setAuthorities(String v) { this.authorities = v; }

    public static class Key implements Serializable {
        private String registeredClientId;
        private String principalName;

        public Key() {
        }

        public Key(String registeredClientId, String principalName) {
            this.registeredClientId = registeredClientId;
            this.principalName = principalName;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(registeredClientId, k.registeredClientId)
                    && Objects.equals(principalName, k.principalName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(registeredClientId, principalName);
        }
    }
}
