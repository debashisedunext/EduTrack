package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.feature.auth.Digests;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * A-121 · the six digits, and the three things that have to be true about them.
 *
 * <h2>{@link SecureRandom}, not {@code Math.random}</h2>
 *
 * <p>A predictable code is not a second factor. The whole point of the OTP is
 * that possession of the link does not imply possession of the mailbox — plan
 * §8 — and a code an attacker can compute from the last one they saw restores
 * exactly the position the link alone put them in.
 *
 * <h2>Stored as a hash, for {@code token_hash}'s reason</h2>
 *
 * <p>A-107's migration puts a SHA-256 in {@code token_hash} so that our own
 * database cannot yield a working link. A plaintext {@code otp_hash} beside it
 * would undo that for the second factor: a database dump would hand over both
 * halves, and the column would be misnamed besides.
 *
 * <p><b>Unsalted, unlike a password, and that is deliberate.</b> The value has
 * six digits and a few minutes of life; a work factor buys nothing against a
 * million-entry rainbow table that takes no time to build, and the thing that
 * actually bounds guessing is {@code otp_attempts} — three tries against a
 * persisted counter, not the cost of a hash. Choosing bcrypt here would read as
 * rigour while defending against the wrong attack.
 *
 * <h2>Compared in constant time</h2>
 *
 * <p>{@link MessageDigest#isEqual} rather than {@link String#equals}. Both
 * operands are hex of a fixed length so the timing channel is narrow, but
 * "narrow" is an argument that has to be re-made every time somebody reads the
 * line, and the constant-time call needs no argument at all.
 */
final class ObSignoffOtpCodes {

    /** Six digits, as {@code ObSignoffOtpVerifyRequest.otp}'s pattern requires. */
    static final int DIGITS = 6;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BOUND = 1_000_000;

    private ObSignoffOtpCodes() {
    }

    /**
     * A fresh code, zero-padded.
     *
     * <p>Padding matters: {@code 001234} and {@code 1234} are the same number
     * and different strings, and the contract's pattern accepts only the first.
     * A code rendered unpadded in a mail is one the client cannot type back.
     */
    static String generate() {
        return String.format("%0" + DIGITS + "d", RANDOM.nextInt(BOUND));
    }

    /** What goes in {@code ob_signoffs.otp_hash}. */
    static String hash(String code) {
        return Digests.sha256Hex(code);
    }

    /**
     * Whether a presented code matches a stored hash.
     *
     * <p>Answers {@code false} for a null or blank stored hash rather than
     * throwing — a sign-off nobody has requested a code for has no hash, and
     * that is a wrong answer rather than an error. Bean Validation has already
     * refused a malformed {@code otp} before this is reached, so the shape of
     * the input is not this method's problem.
     */
    static boolean matches(String presented, String storedHash) {
        if (presented == null || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(presented).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
