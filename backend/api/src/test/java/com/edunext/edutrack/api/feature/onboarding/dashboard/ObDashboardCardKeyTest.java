package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-121 · the card vocabulary, against the contract that declares it.
 *
 * <p>Cheap and worth having: every one of these would otherwise be found on the
 * screen, where a wrong wire token renders as a card the client cannot match
 * and drops silently.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ObDashboardCardKeyTest {

    /**
     * Transcribed from {@code ObDashboardCardKey} in
     * {@code contracts/openapi.yaml}, in the contract's own order — which is
     * also the board's, because the contract requires all seven "in
     * {@code ObDashboardCardKey} order".
     *
     * <p>Written out rather than derived from the enum, on
     * {@code PermissionMatrix}'s argument: an expectation computed from the
     * thing under test proves nothing at all.
     */
    private static final List<String> CONTRACT_ORDER = List.of(
            "ongoing-projects", "this-weeks-deadlines", "todays-delivery",
            "overdue-clients", "live", "at-risk", "client-escalations");

    @Test
    @DisplayName("the seven wire tokens are the contract's, in the contract's order")
    void theVocabularyMatchesTheContract() {
        assertThat(ObDashboardCardKey.values())
                .extracting(ObDashboardCardKey::wireName)
                .containsExactlyElementsOf(CONTRACT_ORDER);
    }

    @Test
    @DisplayName("a card serialises as its kebab-case token, never as the constant name")
    void jacksonWritesTheWireToken() throws Exception {
        String json = new ObjectMapper().writeValueAsString(ObDashboardCardKey.THIS_WEEKS_DEADLINES);

        assertThat(json).isEqualTo("\"this-weeks-deadlines\"");
    }

    /**
     * The classification that decides whether a figure may be summed across the
     * summary table's product rows. Pinned here rather than inferred, because
     * getting it wrong is invisible: the board still renders, three of its
     * numbers are simply too large.
     */
    @Test
    @DisplayName("exactly three cards count clients rather than journeys or steps")
    void theClientCountedCardsAreTheThreeAAllocatesDistinctly() {
        assertThat(ObDashboardCardKey.values())
                .filteredOn(ObDashboardCardKey::isClientCounted)
                .extracting(ObDashboardCardKey::wireName)
                .containsExactlyInAnyOrder("overdue-clients", "live", "client-escalations");
    }

    @Test
    void an_unknown_token_resolves_to_empty_rather_than_to_a_default_card() {
        assertThat(ObDashboardCardKey.fromWire("ongoing_projects")).isEmpty();
        assertThat(ObDashboardCardKey.fromWire("")).isEmpty();
        assertThat(ObDashboardCardKey.fromWire(null)).isEmpty();
    }

    @Test
    void a_known_token_round_trips_case_insensitively_and_ignores_surrounding_space() {
        assertThat(ObDashboardCardKey.fromWire("  At-Risk "))
                .contains(ObDashboardCardKey.AT_RISK);
    }
}
