package com.edunext.edutrack.api.feature.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A-023 · SHA-256, hex-encoded.
 *
 * <p>Used for two things that both need a one-way function over a value that
 * is <i>already</i> high-entropy or non-secret — the refresh token's Redis key
 * ({@link RefreshTokenStore}) and the device fingerprint
 * ({@link StoredRefreshToken#fingerprintOf}).
 *
 * <p><b>Deliberately not Argon2id.</b> {@code common/security/PasswordHashing}
 * exists for passwords, where the input is low-entropy and guessable and the
 * whole point is to make each guess expensive. Neither input here is guessable:
 * a refresh token is 256 bits from {@code SecureRandom}, so there is no
 * dictionary to run and nothing a work factor would buy. Reaching for the
 * password hash anyway would put ~175&nbsp;ms and 64&nbsp;MB on every refresh
 * call — a cost paid by every legitimate user, every fifteen minutes, to defend
 * against an attack that cannot happen.
 */
final class Digests {

    private Digests() {
    }

    static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every conformant JRE is required to provide SHA-256, so this is
            // unreachable rather than a case to handle.
            throw new IllegalStateException("SHA-256 is unavailable in this JRE", e);
        }
    }
}
