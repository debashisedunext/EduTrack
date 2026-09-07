package com.edunext.edutrack.api.feature.onboarding.clients;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-102 · the near-duplicate name guard.
 *
 * <p>Two halves worth testing separately: the pairs it <b>must</b> catch,
 * because a miss is two half-onboarded copies of one client, and the pairs it
 * <b>must not</b> catch, because a guard that fires on unrelated clients is one
 * people learn to click past — and the same click dismisses the real one.
 */
class SimilarClientNamesTest {

    @Nested
    @DisplayName("catches what a person would call the same client")
    class Catches {

        /** The contract's own example, and the pair the guard exists for. */
        @Test
        void abbreviatedLegalForms() {
            assertThat(SimilarClientNames.similar("Acme Pvt Ltd", "Acme Private Limited")).isTrue();
        }

        @Test
        void punctuationAndCase() {
            assertThat(SimilarClientNames.similar("ACME  PVT. LTD.", "acme pvt ltd")).isTrue();
        }

        @Test
        @DisplayName("one core contained in the other — 'Acme' against 'Acme Technologies'")
        void containment() {
            assertThat(SimilarClientNames.similar("Acme", "Acme Technologies")).isTrue();
            // Both directions, or the warning would depend on which client was
            // boarded first.
            assertThat(SimilarClientNames.similar("Acme Technologies", "Acme")).isTrue();
        }

        @Test
        @DisplayName("the same words in a different order")
        void wordOrder() {
            assertThat(SimilarClientNames.similar("Bright Horizon Academy", "Horizon Bright Academy"))
                    .isTrue();
        }

        @Test
        @DisplayName("a transposition typo in the core")
        void typo() {
            assertThat(SimilarClientNames.similar("Horizon Acadmey", "Horizon Academy")).isTrue();
        }

        @Test
        @DisplayName("the noise words the guard strips are not what makes two names differ")
        void noiseWords() {
            assertThat(SimilarClientNames.similar("The Little Scholars Group", "Little Scholars"))
                    .isTrue();
            assertThat(SimilarClientNames.similar("Trinity India Pvt Ltd", "Trinity LLP")).isTrue();
        }

        @Test
        @DisplayName("accents fold, so a name typed with and without them is one name")
        void accents() {
            assertThat(SimilarClientNames.similar("École Nouvelle", "Ecole Nouvelle")).isTrue();
        }
    }

    @Nested
    @DisplayName("leaves alone what a person would call two clients")
    class LeavesAlone {

        @Test
        void differentCompanies() {
            assertThat(SimilarClientNames.similar("Acme Pvt Ltd", "Bluebell Schools")).isFalse();
        }

        /**
         * The failure mode that matters most: a guard firing on every short
         * acronym is a guard that gets dismissed on every client, including the
         * one it was right about.
         */
        @Test
        @DisplayName("short acronyms are not each other, however close")
        void shortAcronyms() {
            assertThat(SimilarClientNames.similar("IBM", "IBS")).isFalse();
            assertThat(SimilarClientNames.similar("IIM", "IIT")).isFalse();
        }

        @Test
        @DisplayName("two clients whose names are only legal furniture are not all the same client")
        void namesThatAreOnlyLegalForms() {
            // Both cores strip to nothing, so the tokens are kept as they are
            // rather than collapsing into one empty core that matches every
            // other empty core.
            assertThat(SimilarClientNames.similar("The Company Ltd", "India Group Ltd")).isFalse();
        }

        @Test
        void blankAndNull() {
            assertThat(SimilarClientNames.similar(null, "Acme")).isFalse();
            assertThat(SimilarClientNames.similar("   ", "Acme")).isFalse();
        }
    }

    @Nested
    @DisplayName("the probe is the most distinctive word, because the query is a LIKE")
    class Probe {

        @Test
        void longestCoreWord() {
            assertThat(SimilarClientNames.probe("Sri New Acme Technologies")).isEqualTo("technologies");
        }

        @Test
        @DisplayName("legal forms are never the probe — '%ltd%' would fetch the table")
        void neverALegalForm() {
            assertThat(SimilarClientNames.probe("Acme Pvt Ltd")).isEqualTo("acme");
        }

        @Test
        @DisplayName("null for a name with no usable core, which the repository reads as no candidates")
        void nothingToProbe() {
            assertThat(SimilarClientNames.probe(null)).isNull();
            assertThat(SimilarClientNames.probe("  ")).isNull();
        }
    }

    @Nested
    @DisplayName("the edit budget is proportional, which is what keeps short names apart")
    class EditBudget {

        @Test
        void nothingBelowFourCharacters() {
            assertThat(SimilarClientNames.maxEdits(3)).isZero();
        }

        @Test
        void oneUpToEight() {
            assertThat(SimilarClientNames.maxEdits(4)).isEqualTo(1);
            assertThat(SimilarClientNames.maxEdits(8)).isEqualTo(1);
        }

        @Test
        void twoBeyond() {
            assertThat(SimilarClientNames.maxEdits(9)).isEqualTo(2);
        }

        @Test
        @DisplayName("distance abandons early rather than scoring two unrelated long names in full")
        void boundedDistance() {
            assertThat(SimilarClientNames.editDistanceWithin("horizonacademy", "bluebellschools", 2))
                    .isFalse();
            assertThat(SimilarClientNames.editDistanceWithin("horizonacademy", "horizonacadmey", 2))
                    .isTrue();
        }

        @Test
        @DisplayName("a length gap wider than the budget is refused without scoring at all")
        void lengthGap() {
            assertThat(SimilarClientNames.editDistanceWithin("acme", "acmetechnologies", 2)).isFalse();
        }
    }
}
