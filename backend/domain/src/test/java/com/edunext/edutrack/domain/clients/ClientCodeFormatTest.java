package com.edunext.edutrack.domain.clients;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-028 · the one client-code rule.
 *
 * <p>The interesting case is the hyphen. S-33 has issued codes containing one
 * since B-026, and {@code FieldValidators.alphanumeric()} — applied to the same
 * column by B-030's importer — refused them. Because the code is B-035's upsert
 * key, that refusal is not a message an administrator reads and fixes: it is a
 * client the import silently declines to update.
 */
class ClientCodeFormatTest {

    @ParameterizedTest
    @ValueSource(strings = {"ACME", "ACME-IN", "NORTHWIND_UK", "A1", "9TO5", "acme"})
    @DisplayName("the codes S-33 issues are valid")
    void theCodesTheFormIssuesAreValid(String candidate) {
        assertThat(ClientCodeFormat.isValid(candidate)).isTrue();
    }

    @Test
    @DisplayName("a hyphenated code is valid — the disagreement B-028 resolved")
    void aHyphenatedCodeIsValid() {
        assertThat(ClientCodeFormat.isValid("ACME-IN")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-ACME", "_ACME", "ACME LTD", "ACME.IN", "ACME/IN", "ACME@1", ""})
    @DisplayName("punctuation that is not a hyphen or underscore, and a leading one, are refused")
    void otherPunctuationIsRefused(String candidate) {
        assertThat(ClientCodeFormat.isValid(candidate)).isFalse();
    }

    @Test
    @DisplayName("null is not a code")
    void nullIsNotACode() {
        assertThat(ClientCodeFormat.isValid(null)).isFalse();
    }

    /**
     * The bounds are annotation constants on {@code ClientWriteRequest}. Asserted
     * because {@code @Size} needs compile-time literals, so nothing else would
     * fail if one drifted from {@code clients.client_code VARCHAR(20)}.
     */
    @Test
    @DisplayName("the length bounds are the column's")
    void theLengthBoundsAreTheColumns() {
        assertThat(ClientCodeFormat.MIN_LENGTH).isEqualTo(2);
        assertThat(ClientCodeFormat.MAX_LENGTH).isEqualTo(20);
    }
}
