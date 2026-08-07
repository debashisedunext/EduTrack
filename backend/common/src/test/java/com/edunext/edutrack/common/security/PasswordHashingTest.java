package com.edunext.edutrack.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-020 · proves the hashing half of blueprint §10.3.
 *
 * <p>Argon2id is slow by design, so this suite deliberately hashes only a
 * handful of times.
 */
class PasswordHashingTest {

    private final PasswordEncoder encoder = PasswordHashing.argon2id();

    @Test
    @DisplayName("the encoded hash carries the §10.3 cost parameters")
    void encodesWithBlueprintParameters() {
        String hash = encoder.encode("Correct-Horse-1!");

        // The parameters are encoded into the hash itself, which makes this the
        // only assertion that actually pins them: a future edit to
        // PasswordHashing that halves the memory cost still passes every
        // encode/matches test, because such a hash verifies perfectly well.
        // It is only wrong. m=65536 is 64 MB in KiB; t=3 iterations; p=1.
        assertThat(hash).startsWith("$argon2id$v=19$m=65536,t=3,p=1$");
    }

    @Test
    @DisplayName("a correct password verifies and an incorrect one does not")
    void verifiesOnlyTheCorrectPassword() {
        String hash = encoder.encode("Correct-Horse-1!");

        assertThat(encoder.matches("Correct-Horse-1!", hash)).isTrue();
        assertThat(encoder.matches("correct-horse-1!", hash)).isFalse();
        assertThat(encoder.matches("", hash)).isFalse();
    }

    @Test
    @DisplayName("the same password hashes differently every time")
    void saltsEveryHash() {
        String first = encoder.encode("Correct-Horse-1!");
        String second = encoder.encode("Correct-Horse-1!");

        // Without a per-hash salt, identical passwords produce identical rows —
        // and the users table becomes a report of which accounts share one.
        assertThat(first).isNotEqualTo(second);
        assertThat(encoder.matches("Correct-Horse-1!", first)).isTrue();
        assertThat(encoder.matches("Correct-Horse-1!", second)).isTrue();
    }
}
