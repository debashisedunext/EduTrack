package com.edunext.edutrack.domain.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-028 · the one email rule, pinned in both directions.
 *
 * <p>The cases that matter are the ones where the three previous rules
 * <em>disagreed</em> — an address with no dotted TLD. Everything else was
 * already refused everywhere and is here so a future relaxation is deliberate.
 */
class EmailFormatTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "sara@acme.example",
            "accounts.payable@acme.co.in",
            "support+tickets@bluewave.example",
            "a@b.co",
    })
    @DisplayName("ordinary addresses are accepted")
    void ordinaryAddressesAreAccepted(String candidate) {
        assertThat(EmailFormat.isValid(candidate)).isTrue();
    }

    /**
     * <b>The whole reason this class exists.</b> Jakarta's {@code @Email} — which
     * B-026 put on {@code primaryEmail}, {@code supportEmail} and
     * {@code billingEmail} and B-027 put on the contact address — accepts every
     * one of these. B-030's importer refused them. So S-33 created contacts
     * D-036 could only ever bounce mail to, and rows B-035 would later reject on
     * re-import.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "accounts@acme",
            "bob@localhost",
            "sara@acme.",
            "sara@.example",
    })
    @DisplayName("an address with no dotted TLD is refused — the rule the form used to permit")
    void addressesWithoutATldAreRefused(String candidate) {
        assertThat(EmailFormat.isValid(candidate)).isFalse();
    }

    /**
     * A pasted distribution list is the most common thing that arrives in one of
     * these boxes, and it would otherwise be stored whole as one address.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "sara@acme.example, ravi@acme.example",
            "sara@acme.example; ravi@acme.example",
            "sara acme.example",
            "acme.example",
            "@acme.example",
            "sara@@acme.example",
    })
    @DisplayName("the malformed shapes that actually reach these fields are refused")
    void malformedShapesAreRefused(String candidate) {
        assertThat(EmailFormat.isValid(candidate)).isFalse();
    }

    /**
     * Bean Validation ran its pattern against the raw value while every caller
     * stored the trimmed one, so an address copied out of a spreadsheet cell was
     * refused as malformed and the identical address typed by hand was accepted.
     */
    @Test
    @DisplayName("surrounding whitespace is trimmed before the pattern is applied")
    void surroundingWhitespaceIsTrimmed() {
        assertThat(EmailFormat.isValid("  sara@acme.example \n")).isTrue();
    }

    /**
     * Absent is not malformed. Every column this guards is nullable, and the
     * callers that require an address say so with their own {@code @NotBlank} —
     * splitting "missing" from "wrong" is the distinction an administrator acts
     * on differently.
     */
    @Test
    @DisplayName("null and blank are not valid, and are the caller's to interpret")
    void nullAndBlankAreNotValid() {
        assertThat(EmailFormat.isValid(null)).isFalse();
        assertThat(EmailFormat.isValid("")).isFalse();
        assertThat(EmailFormat.isValid("   ")).isFalse();
    }

    @Test
    @DisplayName("the message names the field, so four screens word it identically")
    void theMessageNamesTheField() {
        assertThat(EmailFormat.message("supportEmail"))
                .isEqualTo("supportEmail is not a well-formed email address.");
    }
}
