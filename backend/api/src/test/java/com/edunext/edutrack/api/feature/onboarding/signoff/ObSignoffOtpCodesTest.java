package com.edunext.edutrack.api.feature.onboarding.signoff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-121 · the six digits.
 */
class ObSignoffOtpCodesTest {

    @Test
    @DisplayName("always six digits, zero-padded")
    void alwaysSixDigits() {
        // Padding is not cosmetic: 001234 and 1234 are the same number and
        // different strings, and the contract's pattern accepts only the first.
        // An unpadded code in a mail is one the client cannot type back.
        IntStream.range(0, 500).forEach(i ->
                assertThat(ObSignoffOtpCodes.generate()).matches("^[0-9]{6}$"));
    }

    @Test
    @DisplayName("does not repeat itself in any obvious way")
    void isNotPredictable() {
        // Not a randomness test — that is SecureRandom's job and this could not
        // prove it anyway. This catches the failure that actually happens: a
        // constant, a counter, or a seed fixed at class load, each of which
        // would sail through every other test in this file.
        Set<String> codes = new HashSet<>();
        IntStream.range(0, 200).forEach(i -> codes.add(ObSignoffOtpCodes.generate()));

        assertThat(codes).hasSizeGreaterThan(150);
    }

    @Test
    @DisplayName("matches its own hash and nothing else")
    void matchesItsOwnHash() {
        String code = ObSignoffOtpCodes.generate();
        String hash = ObSignoffOtpCodes.hash(code);

        assertThat(ObSignoffOtpCodes.matches(code, hash)).isTrue();
        assertThat(ObSignoffOtpCodes.matches("999999", hash)).isFalse();
    }

    @Test
    @DisplayName("the hash is not the code — a database dump yields neither half")
    void storesAHashNotThePlaintext() {
        // token_hash exists so our own database cannot yield a working link
        // (A-107). A plaintext otp_hash beside it would undo that for the
        // second factor and misname the column besides.
        String code = "123456";

        assertThat(ObSignoffOtpCodes.hash(code)).doesNotContain(code).hasSize(64);
    }

    @Test
    @DisplayName("a missing stored hash is a wrong answer, not an exception")
    void nullsAreRefusedNotThrown() {
        // A sign-off nobody has requested a code for has no hash. The verify
        // path reaches this before it reaches its own null check on some
        // orderings, and a throw here would turn a refusal into a 500 that
        // distinguishes itself from every other refusal — which is the one
        // thing this surface must never do.
        assertThat(ObSignoffOtpCodes.matches("123456", null)).isFalse();
        assertThat(ObSignoffOtpCodes.matches("123456", "  ")).isFalse();
        assertThat(ObSignoffOtpCodes.matches(null, ObSignoffOtpCodes.hash("123456"))).isFalse();
    }
}
