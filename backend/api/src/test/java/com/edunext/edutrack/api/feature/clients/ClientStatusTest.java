package com.edunext.edutrack.api.feature.clients;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-026 · the two decisions {@link ClientStatus} carries, asserted rather than
 * left in a comment.
 *
 * <p>Both are the kind that stay correct-looking while being wrong: the wire's
 * {@code isActive} projection changes what §4B.2's ticket-form dropdown offers,
 * and {@code activatedFrom} decides whether a bulk Activate can promote a
 * prospect. Neither would fail a compile, a request or any other test in this
 * package.
 */
class ClientStatusTest {

    @Nested
    @DisplayName("isActive is 'not INACTIVE', not 'is ACTIVE'")
    class ActiveProjection {

        /**
         * The whole reason the projection is what it is. B-025 derived it as
         * {@code status = 'ACTIVE'}, which was exact for two values; the narrow
         * reading applied to three removes every prospect from the client
         * dropdown §4B.2 puts on the ticket create form, silently.
         */
        @Test
        @DisplayName("a prospect is active, so it stays in the ticket form's client dropdown")
        void prospectIsActive() {
            assertThat(ClientStatus.PROSPECT.isActive())
                    .as("§4B.2's ticket-form dropdown filters on this boolean; a prospect "
                            + "reading false would vanish from it with nothing saying why")
                    .isTrue();
        }

        @Test
        @DisplayName("only INACTIVE is inactive")
        void onlyInactiveIsInactive() {
            assertThat(ClientStatus.ACTIVE.isActive()).isTrue();
            assertThat(ClientStatus.INACTIVE.isActive()).isFalse();
        }

        @Test
        @DisplayName("a status nothing recognises reads as inactive, not as active")
        void unknownStatusIsNotActive() {
            assertThat(ClientStatus.isActive("ARCHIVED"))
                    .as("the safe direction for a value nobody can interpret is the one "
                            + "that does not put a client in front of a ticket form")
                    .isFalse();
            assertThat(ClientStatus.isActive(null)).isFalse();
            assertThat(ClientStatus.isActive("")).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"active", "Active", "  ACTIVE  "})
        @DisplayName("parsing is case- and whitespace-insensitive, agreeing with the column collation")
        void parseIsLenient(String raw) {
            assertThat(ClientStatus.parse(raw)).contains(ClientStatus.ACTIVE);
        }

        @Test
        @DisplayName("an unknown status is empty, never coerced to a default")
        void unknownStatusDoesNotParse() {
            assertThat(ClientStatus.parse("ARCHIVED"))
                    .as("a row carrying a status this enum does not know is a database "
                            + "somebody edited by hand; reading it as ACTIVE hides that")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("activatedFrom — what PATCH /clients/{id}/status may write")
    class StatusSetter {

        /**
         * The half that would have been easy to miss. With Prospect already
         * active by {@link ClientStatus#isActive()}, writing {@code ACTIVE}
         * anyway would let S-32's bulk Activate turn a shortlist of prospects
         * into contracted clients — a commercial fact changed by a checkbox,
         * with nothing recording that it happened.
         */
        @Test
        @DisplayName("activating a prospect leaves it a prospect")
        void activatingAProspectIsANoOp() {
            assertThat(ClientStatus.activatedFrom("PROSPECT", true)).isEqualTo("PROSPECT");
        }

        @Test
        @DisplayName("deactivating a prospect is INACTIVE, like anything else")
        void deactivatingAProspectWorks() {
            assertThat(ClientStatus.activatedFrom("PROSPECT", false)).isEqualTo("INACTIVE");
        }

        @Test
        @DisplayName("activating an inactive client lands on ACTIVE, not on what it was before")
        void reactivationLandsOnActive() {
            assertThat(ClientStatus.activatedFrom("INACTIVE", true))
                    .as("nothing records the prior status; inventing a history would be "
                            + "worse than the one honest state")
                    .isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("setting the state it already holds is idempotent")
        void idempotent() {
            assertThat(ClientStatus.activatedFrom("ACTIVE", true)).isEqualTo("ACTIVE");
            assertThat(ClientStatus.activatedFrom("INACTIVE", false)).isEqualTo("INACTIVE");
        }

        /**
         * A row written before {@code ck_clients_status} existed, or by a
         * hand-run UPDATE. It reads as inactive, so activating it must be able
         * to repair it rather than preserve the unreadable value.
         */
        @Test
        @DisplayName("an unrecognised stored status is repairable by activating")
        void unknownStatusIsRepairable() {
            assertThat(ClientStatus.activatedFrom("ARCHIVED", true)).isEqualTo("ACTIVE");
            assertThat(ClientStatus.activatedFrom("ARCHIVED", false)).isEqualTo("INACTIVE");
        }
    }

    /**
     * The seam between this enum and {@code ck_clients_status}. Nothing
     * re-checks a Java enum against a MySQL {@code CHECK}, so a fourth value
     * added to one and not the other would not fail a request, a save or a
     * build — it would mean the form offering a status the database refuses,
     * discovered as a 500. {@code ClientMasterIT} asserts the constraint's own
     * definition; this fails first, and cheaply, if the set here moves.
     */
    @Test
    @DisplayName("the vocabulary is exactly the three §4B.2 names")
    void vocabularyIsTheBlueprintsThree() {
        assertThat(ClientStatus.CODES)
                .as("blueprint §4B.2 Identity group: Active / Inactive / Prospect. "
                        + "Adding a fourth means a migration for ck_clients_status and a "
                        + "contract change to ClientStatus, in the same commit as this line")
                .containsExactly("ACTIVE", "INACTIVE", "PROSPECT");
    }

    @Test
    @DisplayName("the support plans are the union of the blueprint's three and the seeded BASIC")
    void supportPlanVocabulary() {
        assertThat(ClientSupportPlan.CODES)
                .as("§4B.2 names Standard/Premium/Enterprise and ReferenceDataFixture "
                        + "seeds two clients on BASIC; dropping it orphans them")
                .containsExactly("BASIC", "STANDARD", "PREMIUM", "ENTERPRISE");

        assertThat(ClientSupportPlan.parse("premium")).contains(ClientSupportPlan.PREMIUM);
        assertThat(ClientSupportPlan.parse("Gold")).isEmpty();
    }
}
