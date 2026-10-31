package org.openbased.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openbased.config.OpenBasedProperties;
import org.openbased.security.Scopes;

class RolePermissionsTest {

    @AfterEach
    void restoreDefaults() {
        Role.USER.configure(null);
    }

    @Test
    void userRoleCanDropDownloads() {
        assertThat(Role.USER.permissions()).contains(Scopes.MEDIA_DOWNLOAD);
        OpenBasedProperties properties = new OpenBasedProperties();
        properties.getRoles().setUser(Set.of(Scopes.OPENID, Scopes.MEDIA_READ, Scopes.MEDIA_STREAM));
        new RolePermissions(properties);
        assertThat(Role.USER.permissions()).containsExactlyInAnyOrder(Scopes.OPENID, Scopes.MEDIA_READ,
                Scopes.MEDIA_STREAM);
        assertThat(Role.ADMIN.permissions()).contains(Scopes.MEDIA_DOWNLOAD);
    }

    @Test
    void unknownPermissionsStopStartup() {
        OpenBasedProperties properties = new OpenBasedProperties();
        properties.getRoles().setUser(Set.of(Scopes.MEDIA_READ, "media.downlaod"));
        assertThatThrownBy(() -> new RolePermissions(properties)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media.downlaod");
        assertThat(Role.USER.permissions()).isEqualTo(Role.USER.defaultPermissions());
    }
}
