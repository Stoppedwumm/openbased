package org.openbased.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The standard OpenBased scopes. Scope names double as permission names: a request succeeds only if the
 * token carries the scope <em>and</em> the user holds the permission of the same name.
 */
public final class Scopes {

    public static final String OPENID = "openid";
    public static final String PROFILE = "profile";
    public static final String EMAIL = "email";

    public static final String MEDIA_READ = "media.read";
    public static final String MEDIA_STREAM = "media.stream";
    public static final String MEDIA_WRITE = "media.write";
    public static final String MEDIA_DELETE = "media.delete";

    public static final String LIBRARY_READ = "library.read";
    public static final String LIBRARY_WRITE = "library.write";

    public static final String HISTORY_READ = "history.read";
    public static final String HISTORY_WRITE = "history.write";

    public static final String UPLOAD = "upload";
    public static final String USERS_READ = "users.read";
    public static final String USERS_WRITE = "users.write";

    public static final String PLUGINS_READ = "plugins.read";
    public static final String PLUGINS_MANAGE = "plugins.manage";

    public static final String SERVER_READ = "server.read";
    public static final String SERVER_ADMIN = "server.admin";

    public static final Set<String> ALL = new LinkedHashSet<>(List.of(
            OPENID, PROFILE, EMAIL,
            MEDIA_READ, MEDIA_STREAM, MEDIA_WRITE, MEDIA_DELETE,
            LIBRARY_READ, LIBRARY_WRITE,
            HISTORY_READ, HISTORY_WRITE,
            UPLOAD, USERS_READ, USERS_WRITE,
            PLUGINS_READ, PLUGINS_MANAGE,
            SERVER_READ, SERVER_ADMIN));

    private Scopes() {
    }
}
