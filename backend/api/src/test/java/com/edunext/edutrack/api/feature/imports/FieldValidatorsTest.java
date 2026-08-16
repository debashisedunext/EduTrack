package com.edunext.edutrack.api.feature.imports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** B-030 · blueprint §4B.3's validation rules, one test class for all of them. */
class FieldValidatorsTest {

    /**
     * B-028 · <b>{@code clientCode} is no longer one of this rule's callers.</b>
     * It is a generic field rule and stays for the fields where letters and
     * digits really is the constraint (B-038's employee code); the client
     * master's own rule permits hyphens and lives on {@code ClientCodeFormat},
     * applied by {@code ClientImportSchema}. The wording below is no longer
     * about the upsert match, because the field it referred to has left.
     */
    @Nested
    class Alphanumeric {

        @ParameterizedTest
        @ValueSource(strings = {"ACME", "acme", "A1", "12345", "Northwind2026"})
        void accepts(String code) {
            assertThat(FieldValidators.alphanumeric().validate(code)).isEmpty();
        }

        @ParameterizedTest
        @DisplayName("punctuation and spacing are rejected")
        @ValueSource(strings = {"AC-ME", "AC ME", "AC_ME", "ACME!", "ACMÉ"})
        void rejects(String code) {
            assertThat(FieldValidators.alphanumeric().validate(code))
                    .contains("Must be letters and numbers only");
        }
    }

    @Nested
    class Email {

        @ParameterizedTest
        @ValueSource(strings = {
                "someone@example.com",
                "first.last@sub.example.co.in",
                "support+tickets@acme.example"})
        void accepts(String email) {
            assertThat(FieldValidators.email().validate(email)).isEmpty();
        }

        @ParameterizedTest
        @DisplayName("what actually turns up in spreadsheets")
        @ValueSource(strings = {
                "someone.example.com",          // no @
                "someone@example",              // bare domain, no TLD
                "someone@example.com,",         // trailing comma from a copied list
                "a@b.com b@c.com",              // two addresses in one cell
                "@example.com"})
        void rejects(String email) {
            assertThat(FieldValidators.email().validate(email)).contains("Invalid email");
        }
    }

    @Nested
    class Country {

        @ParameterizedTest
        @DisplayName("the same answer typed three ways")
        @ValueSource(strings = {"India", "IN", "IND", "india", "  India  "})
        void accepts(String country) {
            assertThat(FieldValidators.isoCountry().validate(country)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"Narnia", "XX", "Indiaa"})
        void rejects(String country) {
            assertThat(FieldValidators.isoCountry().validate(country))
                    .contains("Not a recognised country");
        }
    }

    @Nested
    class Dates {

        @Test
        void acceptsIso() {
            assertThat(FieldValidators.isoDate().validate("2026-04-01")).isEmpty();
        }

        @ParameterizedTest
        @DisplayName("ambiguous and impossible dates are rejected, not guessed at")
        @ValueSource(strings = {"01/04/2026", "2026-13-01", "2026-02-30", "April 2026"})
        void rejects(String value) {
            // 01/04/2026 is the important one: it is 1 April to half the world
            // and 4 January to the other half, and an importer that picks one
            // is wrong for the other half silently.
            assertThat(FieldValidators.isoDate().validate(value))
                    .contains("Not a date — expected YYYY-MM-DD");
        }
    }

    @Nested
    class Enums {

        @Test
        void acceptsAnyCase() {
            assertThat(FieldValidators.oneOf(java.util.List.of("ACTIVE", "INACTIVE"))
                    .validate("active")).isEmpty();
        }

        @Test
        @DisplayName("the reason lists what is allowed — the user is fixing a cell, not reading docs")
        void namesTheAllowedValues() {
            assertThat(FieldValidators.oneOf(java.util.List.of("ACTIVE", "INACTIVE"))
                    .validate("PENDING"))
                    .contains("Must be one of: ACTIVE, INACTIVE");
        }
    }

    @Nested
    class MaxLength {

        @Test
        void acceptsExactlyTheLimit() {
            assertThat(FieldValidators.maxLength(4).validate("ACME")).isEmpty();
        }

        @Test
        void rejectsOneOver() {
            assertThat(FieldValidators.maxLength(4).validate("ACMES"))
                    .contains("Longer than 4 characters");
        }
    }
}
