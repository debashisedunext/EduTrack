package com.edunext.edutrack.api.feature.masters.resources;

import com.edunext.edutrack.api.feature.auth.PasswordComplexity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-011, B-013 · the generated temporary password satisfies blueprint §10.3
 * <b>every time</b>, not by construction-and-hope.
 *
 * <p>The generator places one character of each class before filling the rest,
 * so the four rules hold by construction — and that argument is exactly the kind
 * that survives a refactor which quietly breaks it. These sample it instead.
 *
 * <p><b>B-013 pointed the assertion at {@link PasswordComplexity}</b>, the rule
 * {@code @ValidPassword} applies, instead of at a regex restatement of §10.3
 * that lived in this file. The restatement was the point of the change: a test
 * that re-describes the rule it is checking cannot fail when the rule moves, and
 * a generator that has fallen behind the policy produces passwords nothing
 * rejects — no request 400s, no login fails, because composition is checked when
 * a password is <i>set</i> and never again. The gap would have shown up as an
 * auditor's question rather than a red build. Now the policy and the generator
 * are one edit apart, and this is the test that fails.
 */
class TemporaryPasswordsTest {

    /**
     * Enough that a one-in-a-thousand construction bug shows up, cheap enough
     * to run on every build: 2,000 draws is a few milliseconds.
     */
    private static final int SAMPLES = 2_000;

    @Test
    @DisplayName("every generated password is one §10.3 itself would accept")
    void alwaysMeetsComplexity() {
        for (int i = 0; i < SAMPLES; i++) {
            String password = TemporaryPasswords.generate();

            // The whole policy in one call — the four classes and the length
            // bounds together, which is the question a generator is asking.
            // Named per class below, because "isCompliant was false" on its own
            // sends whoever broke it looking through four predicates.
            assertThat(PasswordComplexity.missingClasses(password))
                    .as("sample %d is missing a character class §10.3 requires", i)
                    .isEmpty();

            assertThat(PasswordComplexity.isCompliant(password))
                    .as("sample %d would be accepted by §10.3", i)
                    .isTrue();
        }
    }

    /**
     * The bound that can break without anybody touching this file.
     *
     * <p>{@code MIN_LENGTH} is A-028's to change, and raising it past sixteen
     * would leave this generator issuing passwords the organisation's own policy
     * rejects — silently, for the reason in the class comment. Asserted at both
     * ends because a ceiling lowered below the generated length is the same
     * failure in the other direction.
     */
    @Test
    @DisplayName("sixteen characters sits inside §10.3's bounds, wherever those move to")
    void lengthStaysInsideThePolicyBounds() {
        assertThat(TemporaryPasswords.generate())
                .hasSizeGreaterThanOrEqualTo(PasswordComplexity.MIN_LENGTH)
                .hasSizeLessThanOrEqualTo(PasswordComplexity.MAX_LENGTH)
                // Still pinned to the generator's own choice, so a change to it
                // is a decision somebody makes rather than one that rides along.
                .hasSize(16);
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
        Set<PasswordComplexity.CharacterClass> classesSeen = new HashSet<>();
        for (int i = 0; i < SAMPLES; i++) {
            classesSeen.add(classOf(TemporaryPasswords.generate().substring(0, 1)));
        }
        assertThat(classesSeen).containsExactlyInAnyOrder(PasswordComplexity.CharacterClass.values());
    }

    /**
     * Which class a single character belongs to, asked of the policy rather than
     * re-derived — this file had its own copy of the four predicates and B-013
     * removed it. {@code SYMBOL} is by exclusion, so a one-character string
     * satisfies exactly one class and the stream below finds it.
     */
    private static PasswordComplexity.CharacterClass classOf(String character) {
        return java.util.Arrays.stream(PasswordComplexity.CharacterClass.values())
                .filter(c -> c.isSatisfiedBy(character))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the generator produced a character in none of §10.3's classes: " + character));
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
