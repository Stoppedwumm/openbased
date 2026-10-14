package org.openbased.security.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Hashes token values for storage. Hashing an already hashed value returns it unchanged. */
final class TokenHashes {

    static final String PREFIX = "sha256:";

    private TokenHashes() {
    }

    static String hash(String value) {
        if (value == null || value.startsWith(PREFIX)) {
            return value;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
