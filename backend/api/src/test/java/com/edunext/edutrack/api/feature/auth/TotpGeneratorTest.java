package com.edunext.edutrack.api.feature.auth;

import org.apache.commons.codec.binary.Base32;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-029 · RFC 6238 conformance.
 *
 * <p><b>This class is the reason {@link TotpGenerator} is allowed to be
 * hand-written.</b> RFC 6238 Appendix B publishes reference values — a fixed
 * secret, specific instants, and the exact codes a correct implementation must
 * produce. Running them turns "we think the truncation is right" into a
 * statement the specification makes rather than one the author does.
 *
 * <p>The RFC's vectors are given for 8 digits; this implementation emits the 6
 * that §5.2 and every authenticator app use, so the expectations below are the
 * RFC's values truncated to their last six digits — which is exactly what
 * reducing modulo 10^6 rather than 10^8 produces.
 */
class TotpGeneratorTest {

    /**
     * RFC 6238 Appendix B's seed for HMAC-SHA1: the ASCII string
     * "12345678901234567890", 20 bytes. The RFC prints it in hex; authenticator
     * apps and this implementation speak Base32, so it is converted here rather
     * than hardcoded in a second encoding that could silently disagree.
     */
    private static final String RFC_SECRET =
            new Base32().encodeAsString("12345678901234567890".getBytes(StandardCharsets.US_ASCII))
                    .replace("=", "");

    private final TotpGenerator generator = new TotpGenerator();

    // ── RFC 6238 Appendix B ─────────────────────────────────────────────────

    /**
     * The published vectors. Each row is (unix time, the RFC's 8-digit value,
     * the 6-digit code this implementation must produce).
     *
     * <p>The 1234567890 and 2000000000 rows matter most: they straddle a
     * 2^31-second boundary, which is where an implementation that computes the
     * counter in a 32-bit int silently starts producing wrong codes.
     */
    @ParameterizedTest(name = "t={0} → {2}")
    @CsvSource({
            "59,          94287082, 287082",
            "1111111109,  07081804, 081804",
            "1111111111,  14050471, 050471",
            "1234567890,  89005924, 005924",
            "2000000000,  69279037, 279037",
            "20000000000, 65353130, 353130"})
    @DisplayName("matches RFC 6238 Appendix B's published values")
    void matchesTheRfcVectors(long epochSecond, String rfcEightDigit, String expectedSixDigit) {
        Instant when = Instant.ofEpochSecond(epochSecond);

        String actual = generator.codeFor(RFC_SECRET, generator.timeStepAt(when));

        assertThat(actual).isEqualTo(expectedSixDigit);
        assertThat(rfcEightDigit)
                .as("the 6-digit code is the RFC's 8-digit value's last six — "
                        + "if this ever fails, the truncation modulus is wrong")
                .endsWith(actual);
    }

    /**
     * The 2000000000 vector above passes through this boundary. An
     * implementation that packs the counter into an {@code int} rather than a
     * {@code long} works perfectly until January 2038 and then does not.
     */
    @Test
    @DisplayName("the counter is 64-bit — codes past 2038 are still correct")
    void theCounterSurvivesThe2038Boundary() {
        Instant past2038 = Instant.ofEpochSecond(20_000_000_000L);

        assertThat(generator.codeFor(RFC_SECRET, generator.timeStepAt(past2038)))
                .isEqualTo("353130");
    }

    // ── shape ───────────────────────────────────────────────────────────────

    /**
     * A code of 4321 is "004321". Formatting it as a bare number drops the
     * leading zeroes and rejects roughly one honest attempt in ten.
     */
    @Test
    @DisplayName("every code is exactly six digits, zero-padded")
    void codesAreAlwaysSixDigits() {
        String secret = generator.newSecret();

        for (long step = 0; step < 500; step++) {
            String code = generator.codeFor(secret, step);
            assertThat(code).hasSize(6).matches("\\d{6}");
        }
    }

    /**
     * The base has to sit exactly on a step boundary for "+29 is the same step"
     * to mean anything. A first draft used 1700000000, which is 20 seconds into
     * its step, so +29 had already rolled over — the assertion failed and the
     * implementation was right. Aligning explicitly says what the test means
     * instead of relying on a chosen constant happening to divide.
     */
    @Test
    @DisplayName("the time step advances once every 30 seconds")
    void theStepIsThirtySeconds() {
        long alignedSecond = 1_700_000_000L / TotpGenerator.PERIOD_SECONDS * TotpGenerator.PERIOD_SECONDS;
        Instant base = Instant.ofEpochSecond(alignedSecond);

        long step = generator.timeStepAt(base);

        assertThat(generator.timeStepAt(base)).isEqualTo(step);
        assertThat(generator.timeStepAt(base.plusSeconds(29)))
                .as("29 seconds after a boundary is still the same step")
                .isEqualTo(step);
        assertThat(generator.timeStepAt(base.plusSeconds(30)))
                .as("30 seconds after a boundary is the next step")
                .isEqualTo(step + 1);
    }

    // ── the secret ──────────────────────────────────────────────────────────

    /**
     * Base32 without padding — '=' is legal but several authenticator apps
     * reject or mangle it inside an {@code otpauth://} secret parameter.
     */
    @Test
    @DisplayName("a generated secret is unpadded Base32 an authenticator can read")
    void secretsAreUnpaddedBase32() {
        String secret = generator.newSecret();

        assertThat(secret).matches("[A-Z2-7]{32}").doesNotContain("=");
    }

    @Test
    @DisplayName("every generated secret is different")
    void secretsAreUnique() {
        assertThat(generator.newSecret()).isNotEqualTo(generator.newSecret());
    }

    // ── verification and the drift window ───────────────────────────────────

    @Test
    @DisplayName("the code for the current step verifies")
    void acceptsTheCurrentCode() {
        String secret = generator.newSecret();
        Instant now = Instant.now();
        String code = generator.codeFor(secret, generator.timeStepAt(now));

        assertThat(generator.verify(secret, code, now, 1)).isPresent();
    }

    /**
     * Phones and servers do not share a clock. One step tolerates ±30 seconds,
     * which covers an unsynchronised phone and the seconds between reading a
     * code and pressing submit.
     */
    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1})
    @DisplayName("a code one step either side is accepted with a window of 1")
    void acceptsAdjacentStepsWithinTheWindow(long offset) {
        String secret = generator.newSecret();
        Instant now = Instant.now();
        String code = generator.codeFor(secret, generator.timeStepAt(now) + offset);

        assertThat(generator.verify(secret, code, now, 1)).isPresent();
    }

    /**
     * The window has to have an edge, or the factor weakens in proportion to how
     * many codes are simultaneously valid.
     */
    @ParameterizedTest
    @ValueSource(longs = {-2, 2, 10})
    @DisplayName("a code outside the window is refused")
    void refusesCodesBeyondTheWindow(long offset) {
        String secret = generator.newSecret();
        Instant now = Instant.now();
        String code = generator.codeFor(secret, generator.timeStepAt(now) + offset);

        assertThat(generator.verify(secret, code, now, 1)).isEmpty();
    }

    /**
     * The step is returned so the caller can record it and refuse a replay —
     * a code stays valid for a whole 30-second window otherwise.
     */
    @Test
    @DisplayName("verification reports which step matched, for replay prevention")
    void reportsTheMatchedStep() {
        String secret = generator.newSecret();
        Instant now = Instant.now();
        long step = generator.timeStepAt(now);

        OptionalLong matched = generator.verify(secret, generator.codeFor(secret, step), now, 1);

        assertThat(matched).hasValue(step);
    }

    @Test
    @DisplayName("a wrong code is refused")
    void refusesAWrongCode() {
        String secret = generator.newSecret();
        Instant now = Instant.now();
        String correct = generator.codeFor(secret, generator.timeStepAt(now));
        String wrong = correct.equals("000000") ? "111111" : "000000";

        assertThat(generator.verify(secret, wrong, now, 1)).isEmpty();
    }

    @Test
    @DisplayName("a code for a different secret is refused")
    void refusesACodeFromAnotherSecret() {
        Instant now = Instant.now();
        String otherSecret = generator.newSecret();
        String code = generator.codeFor(otherSecret, generator.timeStepAt(now));

        assertThat(generator.verify(generator.newSecret(), code, now, 1)).isEmpty();
    }

    /** Malformed input is refused rather than throwing at the caller. */
    @ParameterizedTest
    @ValueSource(strings = {"", "12345", "1234567", "abcdef", "  "})
    @DisplayName("a malformed code is refused without throwing")
    void refusesMalformedCodes(String malformed) {
        String secret = generator.newSecret();

        assertThat(generator.verify(secret, malformed, Instant.now(), 1)).isEmpty();
    }

    @Test
    @DisplayName("nulls are refused without throwing")
    void refusesNulls() {
        assertThat(generator.verify(null, "123456", Instant.now(), 1)).isEmpty();
        assertThat(generator.verify(generator.newSecret(), null, Instant.now(), 1)).isEmpty();
    }

    /**
     * Surrounding whitespace is what a paste from an authenticator app produces,
     * and rejecting it means telling someone their correct code is wrong.
     */
    @Test
    @DisplayName("a pasted code with surrounding whitespace still verifies")
    void toleratesSurroundingWhitespace() {
        String secret = generator.newSecret();
        Instant now = Instant.now();
        String code = generator.codeFor(secret, generator.timeStepAt(now));

        assertThat(generator.verify(secret, "  " + code + " ", now, 1)).isPresent();
    }
}
