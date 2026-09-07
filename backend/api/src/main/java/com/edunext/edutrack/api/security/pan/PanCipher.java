package com.edunext.edutrack.api.security.pan;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * A-113 · AES-256-GCM over {@code pan_ciphertext}.
 *
 * <h2>Package-private on purpose, and that is the audit guarantee</h2>
 *
 * <p>Blueprint §11 requires every unmasked read of a PAN to be audited.
 * {@code AuditInterceptor} derives its terms from <b>mutating</b> routes only —
 * a read is not audited, by design, because auditing every {@code GET} in the
 * product would bury the events that matter. A reveal is a read. So nothing in
 * the interceptor will ever log it, and an audit rule that depends on each
 * caller remembering to log is the "log whose completeness is exactly as good
 * as the last person who remembered" that {@code AuditActions} rejects in its
 * own class note.
 *
 * <p>The resolution is structural rather than procedural: <b>this class is not
 * public, so no other package can decrypt.</b> The only exported route to
 * plaintext is {@link PanService#reveal}, which writes the audit row
 * before it returns the value. B-102 cannot forget to audit a reveal, because
 * B-102 cannot reveal any other way — the compiler enforces what a code review
 * would otherwise have to.
 *
 * <h2>Randomised, and what that costs downstream</h2>
 *
 * <p>A fresh 12-byte nonce per call means the same PAN encrypts to different
 * bytes every time. That is the property that makes GCM safe to reuse a key
 * with, and it is also why {@code pan_ciphertext} carries no UNIQUE index — the
 * migration note spells out that such a constraint would apply cleanly, look
 * exactly like a working guard and never once fire. Uniqueness is
 * {@link PanBlindIndex}'s job.
 *
 * <h2>Stored form: nonce, then ciphertext and tag</h2>
 *
 * <p>The nonce is not a secret and has to be recoverable to decrypt, so it is
 * prefixed rather than stored in a second column. A ten-character PAN occupies
 * 12 + 10 + 16 = 38 bytes, comfortably inside the column's
 * {@code VARBINARY(255)}.
 *
 * <p>GCM is authenticated: a tampered row fails to decrypt rather than
 * returning a different plausible PAN. That matters more here than for a TOTP
 * secret — a silently altered PAN is a wrong identity attached to a real
 * company, and it would propagate into the duplicate guard as a false negative.
 */
final class PanCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final PanKeySource keys;
    private final SecureRandom random = new SecureRandom();

    PanCipher(PanKeySource keys) {
        this.keys = keys;
    }

    /** @param normalisedPan the output of {@link PanFormat#normalise} */
    byte[] encrypt(String normalisedPan) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keys.encryptionKey(), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(normalisedPan.getBytes(StandardCharsets.UTF_8));
            byte[] stored = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, stored, 0, nonce.length);
            System.arraycopy(sealed, 0, stored, nonce.length, sealed.length);
            return stored;
        } catch (GeneralSecurityException e) {
            // No PAN in the message. An exception carrying the plaintext it was
            // protecting ends up in a log file, which is the one place this
            // whole class exists to keep it out of.
            throw new IllegalStateException("A PAN could not be encrypted", e);
        }
    }

    /**
     * @throws IllegalStateException if the stored bytes cannot be decrypted —
     *         a changed key, or a tampered row. Raised rather than returning
     *         null so the failure reads as "this deployment's key does not
     *         match its data" instead of surfacing as a client whose PAN has
     *         silently gone missing.
     */
    String decrypt(byte[] stored) {
        if (stored == null || stored.length <= NONCE_BYTES) {
            throw new IllegalStateException(
                    "A stored PAN ciphertext is shorter than its own nonce, so it was not written "
                            + "by this class. The row is corrupt rather than merely undecryptable.");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keys.encryptionKey(),
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOf(stored, NONCE_BYTES)));
            byte[] plaintext = cipher.doFinal(stored, NONCE_BYTES, stored.length - NONCE_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "A stored PAN could not be decrypted. This normally means "
                            + "edutrack.onboarding.pan.encryption-key has changed since the row was "
                            + "written; it can also mean the row was altered outside the application, "
                            + "which GCM detects rather than decrypting to a different value.", e);
        }
    }
}
