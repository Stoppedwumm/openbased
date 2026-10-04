package org.openbased.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;

import com.nimbusds.jose.jwk.RSAKey;

import org.openbased.common.Ids;

/** Loads the token signing key from the data directory, creating it on first start. */
public final class JwkStore {

    private JwkStore() {
    }

    public static RSAKey loadOrCreate(Path dataDir) {
        Path file = dataDir.resolve("keys").resolve("signing-key.jwk.json");
        try {
            if (Files.exists(file)) {
                return RSAKey.parse(Files.readString(file, StandardCharsets.UTF_8));
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            RSAKey key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(Ids.ulid())
                    .build();
            Files.createDirectories(file.getParent());
            Files.writeString(file, key.toJSONString(), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX file system.
            }
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load signing key " + file, e);
        } catch (NoSuchAlgorithmException | ParseException e) {
            throw new IllegalStateException("Cannot load signing key " + file, e);
        }
    }
}
