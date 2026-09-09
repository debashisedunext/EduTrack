package com.edunext.edutrack.api.feature.onboarding.dashboard;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-127 · the one thing worth pinning without a database: every card the
 * contract's enum declares has a {@code CardPlan} behind it. A card added to
 * {@link ObDashboardCardKey} without a matching entry here throws
 * {@code IllegalStateException} at request time rather than failing a build —
 * this is what turns that into a test failure instead.
 *
 * <p>The union query itself — what each plan actually selects, the cursor,
 * the scope predicates against real rows — is {@code ObDashboardCardItemsIT},
 * which needs the container this class deliberately does not.
 */
class ObDashboardCardItemsRepositoryTest {

    @Test
    void every_card_the_enum_declares_has_a_plan() {
        assertThat(ObDashboardCardItemsRepository.plannedCards())
                .containsExactlyInAnyOrder(ObDashboardCardKey.values());
    }
}
