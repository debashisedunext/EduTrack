package com.edunext.edutrack.api.feature.auth;

import org.apache.commons.codec.binary.Base32;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * A-029 · RFC 6238 time-based one-time passwords. Blueprint §10.1's "2FA
 * enabled? → TOTP challenge", screen S-04.
 *
 * <h2>Why this is written out rather than pulled from a library</h2>
 *
 * <p>The usual and usually-correct instinct is to take a dependency for
 * anything cryptographic. Two things make this the exception.
 *
 * <p><b>Everything it needs is already here.</b> HMAC-SHA1 is
 * {@link javax.crypto.Mac} in the JDK, and Base32 is {@code commons-codec},
 * already on the classpath transitively. A TOTP library would add a
 * dependency to a {@code pom.xml} four streams share, to supply about forty
 * lines.
 *
 * <p><b>And correctness here is provable rather than asserted.</b> RFC 6238
 * Appendix B publishes reference values — specific secrets, specific instants,
 * specific expected codes. {@code TotpGeneratorTest} runs every one of them, so
 * "did we get the truncation right" is answered by the specification itself
 * rather than by the author's confidence. That is a stronger guarantee than
 * most dependencies come with, and it is the reason this file is allowed to
 * exist.
 *
 * <h2>The algorithm, and the two parts that are easy to get wrong</h2>
 *
 * <p>A code is {@code HMAC-SHA1(secret, floor(unixTime / period))}, reduced to
 * six digits. The subtleties:
 *
 * <ul>
 *   <li><b>The counter is eight bytes, big-endian</b>, not a decimal string and
 *       not the raw seconds. Getting this wrong produces codes that look
 *       perfectly plausible and match no authenticator app on earth.</li>
 *   <li><b>Dynamic truncation</b> — the low nibble of the last byte selects
 *       where to read four bytes from, and the top bit of those is masked off.
 *       The mask is not decoration: without it the value is interpreted as a
 *       negative signed integer half the time, and half of all codes come out
 *       wrong.</li>
 * </ul>
 *
 * <p><b>SHA-1, deliberately.</b> It is the default in RFC 6238 and the only
 * algorithm Google Authenticator, Authy and the rest reliably implement — a
 * SHA-256 secret produces codes no ordinary authenticator can generate. SHA-1's
 * weakness is collision resistance, which HMAC does not rely on; HMAC-SHA1
 * remains sound and is what every TOTP deployment uses.
 */
@Component
class TotpGenerator {

    /** RFC 6238 §5.2 and every authenticator app in practice. */
    static final int DIGITS = 6;
    static final int PERIOD_SECONDS = 30;

    private static final String HMAC_ALGORITHM = "HmacSHA1";

    /**
     * 160 bits, matching HMAC-SHA1's block-derived key size and RFC 4226 §4's
     * recommendation. Base32-encodes to 32 characters, which is what a user
     * would have to type if the QR code fails to scan.
     */
    private static final int SECRET_BYTES = 20;

    private static final int[] POWERS_OF_TEN = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000};

    private final SecureRandom random = new SecureRandom();

    /**
     * A fresh shared secret, Base32 without padding — the encoding every
     * authenticator expects in an {@code otpauth://} URI, and the one a user
     * can retype by hand.
     */
    String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        // No padding: '=' is legal Base32 but is rejected or mangled by several
        // authenticator apps when it appears in an otpauth:// secret parameter.
        return new Base32().encodeAsString(bytes).replace("=", "");
    }

    /** The time step a given instant falls in — the counter the code is derived from. */
    long timeStepAt(Instant when) {
        return Math.floorDiv(when.getEpochSecond(), PERIOD_SECONDS);
    }

    /**
     * The code for one specific time step.
     *
     * @param base32Secret the shared secret as stored, Base32
     * @return exactly {@link #DIGITS} digits, left-padded with zeroes — a code
     *         of "004321" is not the same string as "4321", and comparing them
     *         as numbers is how leading-zero codes come to be rejected roughly
     *         one time in ten
     */
    String codeFor(String base32Secret, long timeStep) {
        byte[] key = new Base32().decode(base32Secret);
        byte[] counter = ByteBuffer.allocate(8).putLong(timeStep).array();

        byte[] hash;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            hash = mac.doFinal(counter);
        } catch (GeneralSecurityException e) {
            // HmacSHA1 is required of every conformant JRE, so this is
            // unreachable rather than a case to handle. An empty or malformed
            // secret surfaces here as an InvalidKeyException, which is a
            // programming error in the caller, not a runtime condition.
            throw new IllegalStateException("HMAC-SHA1 is unavailable or the secret is unusable", e);
        }

        // RFC 4226 §5.4 dynamic truncation.
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)      // 0x7F masks the sign bit
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);

        // Left-padded to exactly DIGITS. A code of 4321 is "004321"; formatting
        // it as a bare number is how leading-zero codes come to be rejected
        // roughly one attempt in ten.
        return String.format("%0" + DIGITS + "d", binary % POWERS_OF_TEN[DIGITS]);
    }

    /**
     * Whether a submitted code matches the secret at {@code now}, allowing
     * {@code windowSteps} either side.
     *
     * <h2>Why a window exists at all</h2>
     *
     * <p>The user's phone and this server do not share a clock. A window of one
     * step tolerates ±30 seconds of drift, which covers an unsynchronised phone
     * and the seconds between reading a code and pressing submit. Zero would be
     * technically correct and would reject a meaningful share of honest
     * attempts; large values multiply the number of codes valid at any moment
     * and weaken the factor proportionally.
     *
     * <h2>Constant-time comparison, and why it is not paranoia here</h2>
     *
     * <p>{@link MessageDigest#isEqual} rather than {@link String#equals}. The
     * search space is a million codes, small enough that a byte-by-byte early
     * exit is a usable oracle: an attacker who can distinguish "wrong at the
     * first digit" from "wrong at the fifth" recovers a code far faster than a
     * million guesses. {@code String.equals} short-circuits on the first
     * differing character, so this is a real distinction rather than a
     * ritual one.
     *
     * @return the matched time step, or empty when nothing in the window matches
     */
    java.util.OptionalLong verify(String base32Secret, String submittedCode, Instant now, int windowSteps) {
        if (base32Secret == null || submittedCode == null) {
            return java.util.OptionalLong.empty();
        }

        String candidate = submittedCode.trim();
        if (candidate.length() != DIGITS) {
            return java.util.OptionalLong.empty();
        }

        long current = timeStepAt(now);
        // Every step in the window is checked even after a match, for the same
        // reason PasswordPolicy's history loop does not short-circuit: stopping
        // early would let the caller distinguish "matched the current step" from
        // "matched the one before" by timing, which narrows their clock estimate.
        java.util.OptionalLong matched = java.util.OptionalLong.empty();
        for (long step = current - windowSteps; step <= current + windowSteps; step++) {
            String expected = codeFor(base32Secret, step);
            if (MessageDigest.isEqual(
                    expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    candidate.getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                    && matched.isEmpty()) {
                matched = java.util.OptionalLong.of(step);
            }
        }
        return matched;
    }
}
