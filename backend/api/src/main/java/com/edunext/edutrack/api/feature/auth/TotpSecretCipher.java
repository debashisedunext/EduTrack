package com.edunext.edutrack.api.feature.auth;

import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * A-029 · encrypts the TOTP shared secret at rest.
 *
 * <h2>Encrypted, not hashed — and why that is not a weaker choice</h2>
 *
 * <p>Everything else this package stores one-way: passwords through Argon2id,
 * refresh tokens and reset tokens through SHA-256. A TOTP secret cannot follow
 * them, because verifying a code means recomputing {@code HMAC-SHA1(secret,
 * step)} — the server needs the secret itself, so a one-way function would make
 * it unusable. That is a property of the algorithm rather than a shortcut.
 *
 * <p>What encryption buys is the thing hashing would have bought: <b>a database
 * dump on its own is worthless</b>. The key lives in configuration, supplied by
 * the environment outside {@code local}, so a leaked backup, a replica or a
 * {@code SELECT * FROM users} yields ciphertext. It does not defend against an
 * attacker who has both the database and the application's configuration —
 * nothing that has to be reversible can.
 *
 * <h2>AES-256-GCM via Spring Security</h2>
 *
 * <p>{@link Encryptors#delux} is AES-256 in GCM with a random IV per call and a
 * PBKDF2-derived key. GCM is authenticated: tampering with stored ciphertext
 * produces a decryption failure rather than a silently different secret, which
 * matters because a secret that decrypts to garbage would present as "your
 * authenticator is wrong" to a user whose authenticator is fine.
 *
 * <p><b>The salt is derived from the key rather than random per row.</b> A
 * per-row salt would have to be stored beside the ciphertext, and the column is
 * a single {@code VARCHAR}; deriving it deterministically keeps the stored form
 * to one value. It is not a password-hashing salt and is not doing that job —
 * GCM's per-call random IV is what makes two encryptions of the same secret
 * differ, and that is already handled.
 */
@Component
class TotpSecretCipher {

    private final TextEncryptor encryptor;

    TotpSecretCipher(TotpProperties properties) {
        // Encryptors.delux wants the salt as hex. Derived from the key with
        // SHA-256 so it is stable across restarts — a random salt would make
        // every previously stored secret undecryptable on the next boot, which
        // is an outage that presents as "2FA stopped working for everyone".
        String salt = HexFormat.of().formatHex(sha256(properties.encryptionKey()), 0, 8);
        this.encryptor = Encryptors.delux(properties.encryptionKey(), salt);
    }

    /** @param base32Secret the plaintext shared secret, as an authenticator reads it */
    String encrypt(String base32Secret) {
        return encryptor.encrypt(base32Secret);
    }

    /**
     * @throws IllegalStateException if the stored value cannot be decrypted —
     *         which means the key has changed since it was written. Raised
     *         rather than returning null so the failure reads as "this
     *         deployment's key does not match its data" instead of surfacing as
     *         a user whose correct codes are all rejected.
     */
    String decrypt(String storedSecret) {
        try {
            return encryptor.decrypt(storedSecret);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "A stored TOTP secret could not be decrypted. This normally means "
                            + "edutrack.auth.totp.encryption-key has changed since the secret was "
                            + "written; the affected users must re-enrol.", e);
        }
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable in this JRE", e);
        }
    }
}
