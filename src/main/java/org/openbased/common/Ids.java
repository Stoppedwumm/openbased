package org.openbased.common;

import java.security.SecureRandom;

/**
 * Generates ULIDs: 26 character, Crockford base32, lexicographically sortable by creation time.
 */
public final class Ids {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String ulid() {
        char[] out = new char[26];
        long time = System.currentTimeMillis();
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (time & 31)];
            time >>>= 5;
        }
        for (int i = 10; i < 26; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(32)];
        }
        return new String(out);
    }

    /** A ULID with a type prefix, e.g. {@code media_01J9...}. */
    public static String prefixed(String prefix) {
        return prefix + "_" + ulid();
    }

    /** A random string of URL-safe alphanumeric characters. */
    public static String randomAlphanumeric(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
