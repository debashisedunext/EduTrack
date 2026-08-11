package com.edunext.edutrack.api.feature.auth;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * A-028 · the four character classes of blueprint §10.3.
 *
 * <p>The interesting cases are the near-misses — a password that satisfies three
 * of four rules is exactly what a real user submits, and exactly what a
 * short-circuiting or over-eager implementation gets wrong.
 */
class PasswordComplexityValidatorTest {

    private PasswordComplexityValidator validator;
    private ConstraintValidatorContext context;

    @BeforeEach
    void setUp() {
        validator = new PasswordComplexityValidator();
        // Deep stubs because the failure path chains
        // buildConstraintViolationWithTemplate(..).addConstraintViolation().
        context = mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS);
    }

    // ── accepted ────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "Chosen-By-Me-9!",
            "aA1!aaaa",
            "P@ssw0rd",
            "Tr0ub4dor&3",
            "correct Horse 9 battery!"})
    @DisplayName("a password with all four classes is accepted")
    void acceptsAllFourClasses(String password) {
        assertThat(validator.isValid(password, context)).isTrue();
    }

    /**
     * Passwords are utf8mb4 end to end. A rule that only recognises ASCII tells
     * a user whose memorable phrase is in their own alphabet that their letters
     * do not count as letters.
     */
    @Test
    @DisplayName("non-ASCII letters count as letters")
    void acceptsNonAsciiLetters() {
        assertThat(validator.isValid("Ünïcöde9!", context)).isTrue();
    }

    /**
     * A fixed symbol list like {@code !@#$%^&*} silently refuses these, with a
     * message insisting the user add the symbol they just added.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Password9£", "Password9€", "Password9—", "Password9§"})
    @DisplayName("symbols outside the usual ASCII eight are still symbols")
    void acceptsUnusualSymbols(String password) {
        assertThat(validator.isValid(password, context)).isTrue();
    }

    // ── refused, one class at a time ────────────────────────────────────────

    @Test
    @DisplayName("no upper-case letter is refused")
    void refusesMissingUpper() {
        assertThat(validator.isValid("chosen-by-me-9!", context)).isFalse();
    }

    @Test
    @DisplayName("no lower-case letter is refused")
    void refusesMissingLower() {
        assertThat(validator.isValid("CHOSEN-BY-ME-9!", context)).isFalse();
    }

    @Test
    @DisplayName("no digit is refused")
    void refusesMissingDigit() {
        assertThat(validator.isValid("Chosen-By-Me-Now!", context)).isFalse();
    }

    @Test
    @DisplayName("no symbol is refused")
    void refusesMissingSymbol() {
        assertThat(validator.isValid("ChosenByMe99", context)).isFalse();
    }

    /**
     * The exact case A-028 exists to close: long, lower-case, and previously
     * accepted by A-026 and A-027, which enforced only length.
     */
    @Test
    @DisplayName("a long all-lower-case password is refused")
    void refusesTheLengthOnlyPassword() {
        assertThat(validator.isValid("aaaaaaaaaaaaaaaa", context)).isFalse();
    }

    /**
     * A trailing space is the most common accidental "symbol" there is. Letting
     * it discharge the requirement would pass {@code "Password1 "} while
     * teaching the user nothing.
     */
    @Test
    @DisplayName("whitespace does not count as a symbol")
    void whitespaceIsNotASymbol() {
        assertThat(validator.isValid("Password1 ", context)).isFalse();
    }

    // ── who owns which failure ──────────────────────────────────────────────

    /**
     * Null and blank belong to {@code @NotBlank}, and length to {@code @Size}.
     * Reporting "needs a symbol" for a field the user left empty is noise on top
     * of the message they actually need.
     */
    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("null and empty abstain — that is @NotBlank's message to give")
    void abstainsOnNullAndEmpty(String password) {
        assertThat(validator.isValid(password, context)).isTrue();
    }

    /**
     * Length is {@code @Size}'s rule. A short password that has all four classes
     * passes <i>this</i> constraint and is refused by the other one, so the user
     * is told about the length rather than about a class they satisfied.
     */
    @Test
    @DisplayName("a short password with all four classes passes this constraint")
    void lengthIsNotThisConstraintsJob() {
        assertThat(validator.isValid("aA1!", context)).isTrue();
    }

    // ── the message ─────────────────────────────────────────────────────────

    /**
     * The whole reason this is not a four-lookahead regex: a regex can only ever
     * answer "no", and a form that says "needs a symbol" is one the user can
     * complete.
     */
    @Test
    @DisplayName("the message names the classes that are missing")
    void theMessageNamesWhatIsMissing() {
        validator.isValid("chosenbyme", context);

        verify(context).disableDefaultConstraintViolation();
        verify(context).buildConstraintViolationWithTemplate(
                "Password must contain an upper-case letter, a digit, a symbol.");
    }

    @Test
    @DisplayName("only the missing class is named, not all four")
    void theMessageNamesOnlyWhatIsMissing() {
        validator.isValid("ChosenByMe99", context);

        verify(context).buildConstraintViolationWithTemplate("Password must contain a symbol.");
    }

    /**
     * A validation message is echoed in the response body and written into every
     * log the response passes through. Quoting the rejected value would scatter a
     * near-miss password across the estate.
     */
    @Test
    @DisplayName("the message never quotes the rejected password")
    void theMessageNeverEchoesThePassword() {
        String rejected = "myactualsecret";

        validator.isValid(rejected, context);

        verify(context, org.mockito.Mockito.never())
                .buildConstraintViolationWithTemplate(org.mockito.ArgumentMatchers.contains(rejected));
        verify(context).buildConstraintViolationWithTemplate(anyString());
    }
}
