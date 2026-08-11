package com.edunext.edutrack.api.feature.masters.resources;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-011 · the generated temporary password satisfies blueprint §10.3 <b>every
 * time</b>, not by construction-and-hope.
 *
 * <p>The generator places one character of each class before filling the rest,
 * so the four rules hold by construction — and that argument is exactly the kind
 * that survives a refactor which quietly breaks it. These sample it instead.
 */
class TemporaryPasswordsTest {

    /**
     * Enough that a one-in-a-thousand construction bug shows up, cheap enough
     * to run on every build: 2,000 draws is a few milliseconds.
     */
    private static final int SAMPLES = 2_000;

    @Test
    @DisplayName("every generated password meets §10.3 — upper, lower, digit and symbol")
    void alwaysMeetsComplexity() {
        for (int i = 0; i < SAMPLES; i++) {
            String password = TemporaryPasswords.generate();

            assertThat(password)
                    .as("sample %d", i)
                    .hasSize(16)
                    .matches(".*[A-Z].*")
                    .matches(".*[a-z].*")
                    .matches(".*[0-9].*");

            assertThat(hasSymbol(password))
                    .as("sample %d (%s) contains a symbol", i, password)
                    .isTrue();
        }
    }

    /**
     * The rule {@code PasswordComplexityValidator} actually applies: not a
     * letter, not a digit, not whitespace. Restated here rather than shared,
     * because that class is package-private in {@code feature.auth} and
     * widening it so a test in another feature could call it would be a
     * cross-stream change to satisfy a test.
     */
    private static boolean hasSymbol(String password) {
        return password.chars().anyMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c));
    }

    @Test
    @DisplayName("the confusable characters are absent — O, 0, l, I and 1")
    void avoidsConfusableCharacters() {
        for (int i = 0; i < SAMPLES; i++) {
            assertThat(TemporaryPasswords.generate())
                    .as("sample %d", i)
                    .doesNotContain("O", "0", "l", "I", "1");
        }
    }

    @Test
    @DisplayName("the shell-hostile symbols are absent — a temp password gets pasted into a chat window")
    void avoidsShellHostileSymbols() {
        for (int i = 0; i < SAMPLES; i++) {
            assertThat(TemporaryPasswords.generate())
                    .as("sample %d", i)
                    .doesNotContain("`", "\"", "'", "\\", "$", ";", "(", ")", "{", "}", "[", "]", "<", ">", "|", "&");
        }
    }

    /**
     * The shuffle is what stops every temporary password in the system starting
     * upper-lower-digit-symbol — which would both be recognisable on sight and
     * hand three quarters of the search space back.
     */
    @Test
    @DisplayName("the class of the first character varies, so the shuffle is doing something")
    void firstCharacterIsNotAlwaysUpperCase() {
        Set<String> classesSeen = new HashSet<>();
        for (int i = 0; i < SAMPLES; i++) {
            classesSeen.add(classOf(TemporaryPasswords.generate().charAt(0)));
        }
        assertThat(classesSeen).hasSize(4);
    }

    private static String classOf(char c) {
        if (Character.isUpperCase(c)) {
            return "upper";
        }
        if (Character.isLowerCase(c)) {
            return "lower";
        }
        return Character.isDigit(c) ? "digit" : "symbol";
    }

    @Test
    @DisplayName("two calls do not return the same password")
    void doesNotRepeat() {
        Set<String> seen = IntStream.range(0, SAMPLES)
                .mapToObj(i -> TemporaryPasswords.generate())
                .collect(HashSet::new, Set::add, Set::addAll);

        assertThat(seen).hasSize(SAMPLES);
    }
}
