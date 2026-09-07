package com.edunext.edutrack.api.security.pan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-113 · the normalisation the blind index depends on, and the masking
 * PHASE-2-BUILD-PLAN finding 10 called out.
 */
class PanFormatTest {

    private static final String PAN = "AAAPL1234C";

    @Nested
    @DisplayName("normalise")
    class Normalise {

        @Test
        @DisplayName("trims and upper-cases, so one PAN has one blind index")
        void trimsAndUpperCases() {
            assertThat(PanFormat.normalise("  aaapl1234c  ")).isEqualTo(PAN);
        }

        @Test
        @DisplayName("null in, null out")
        void nullPassesThrough() {
            assertThat(PanFormat.normalise(null)).isNull();
        }

        @Test
        @DisplayName("upper-casing does not follow the default locale")
        void isLocaleIndependent() {
            // Under a Turkish default, "i".toUpperCase() is "İ" — so the same
            // PAN typed on two differently-configured servers would hash to two
            // different blind indices and the UNIQUE constraint would admit
            // both rows. This is the assertion that pins Locale.ROOT.
            Locale original = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("tr"));
                assertThat(PanFormat.normalise("aaipl1234c")).isEqualTo("AAIPL1234C");
            } finally {
                Locale.setDefault(original);
            }
        }
    }

    @Nested
    @DisplayName("isValid")
    class IsValid {

        @Test
        @DisplayName("accepts five letters, four digits and a letter")
        void acceptsWellFormed() {
            assertThat(PanFormat.isValid(PAN)).isTrue();
        }

        @Test
        @DisplayName("accepts it however it was typed")
        void acceptsUntidyInput() {
            assertThat(PanFormat.isValid("  aaapl1234c ")).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "AAAPL1234",      // too short
                "AAAPL1234CC",    // too long
                "AAAP11234C",     // digit in the letter block
                "AAAPL123AC",     // letter in the digit block
                "AAAPL12341",     // digit as the check character
                "AAAPL 1234C",    // internal space
                ""
        })
        @DisplayName("refuses anything that is not PAN-shaped")
        void refusesMalformed(String candidate) {
            assertThat(PanFormat.isValid(candidate)).isFalse();
        }

        @Test
        @DisplayName("refuses null rather than throwing")
        void refusesNull() {
            assertThat(PanFormat.isValid(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("mask")
    class Mask {

        @Test
        @DisplayName("shows the last four characters and nothing else")
        void showsLastFourOnly() {
            assertThat(PanFormat.mask(PAN)).isEqualTo("••••••234C");
        }

        @Test
        @DisplayName("PHASE-2 finding 10: the five-character prefix is not disclosed")
        void hidesTheStructuredPrefix() {
            String masked = PanFormat.mask(PAN);

            // The prototype masked as slice(0,5) + bullets + slice(9), showing
            // six of ten characters. Positions 1-3 are an alphabetic series,
            // position 4 is the holder type and position 5 the surname initial
            // — together they corroborate the client's name sitting beside them
            // on screen. This asserts the regression cannot come back: nothing
            // from the first six characters survives.
            assertThat(masked).doesNotContain("AAAPL");
            assertThat(masked.substring(0, 6)).isEqualTo("•".repeat(6));
        }

        @Test
        @DisplayName("keeps the length, so the field still reads as a PAN")
        void preservesLength() {
            assertThat(PanFormat.mask(PAN)).hasSameSizeAs(PAN);
        }

        @Test
        @DisplayName("masks what it was given, however it was typed")
        void normalisesFirst() {
            assertThat(PanFormat.mask(" aaapl1234c ")).isEqualTo(PanFormat.mask(PAN));
        }

        @Test
        @DisplayName("a value too short to partially disclose is wholly hidden")
        void hidesShortValuesEntirely() {
            assertThat(PanFormat.mask("AB12")).isEqualTo("••••");
        }

        @Test
        @DisplayName("no PAN renders as absent, not as a row of bullets")
        void nullStaysNull() {
            // A row of bullets claims a value is held and withheld. Most
            // ob_clients rows hold no PAN at all, and those two facts must not
            // look identical on screen.
            assertThat(PanFormat.mask(null)).isNull();
        }
    }
}
