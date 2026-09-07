package com.edunext.edutrack.domain.onboarding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** C-114 · {@link ObRagCalculator}. */
class ObRagCalculatorTest {

    // ── no colour at all ─────────────────────────────────────────────

    @Test
    void pendingStepHasNoColour() {
        assertThat(ObRagCalculator.forStep(0, ObJourneyStepStatus.PENDING)).isNull();
        // A step can be born PENDING with zero elapsed time, but a percentage
        // above the threshold must not leak through once the step activates —
        // PENDING means "never started", not "started but on time".
        assertThat(ObRagCalculator.forStep(200, ObJourneyStepStatus.PENDING)).isNull();
    }

    @Test
    void skippedStepHasNoColour() {
        assertThat(ObRagCalculator.forStep(150, ObJourneyStepStatus.SKIPPED)).isNull();
    }

    // ── the ordinary Green/Amber/Red progression ────────────────────

    @ParameterizedTest
    @EnumSource(value = ObJourneyStepStatus.class, names = {"IN_PROGRESS", "WAITING_ON_CLIENT", "DONE"})
    void belowThresholdIsGreen(ObJourneyStepStatus status) {
        assertThat(ObRagCalculator.forStep(74, status, 75)).isEqualTo(ObRag.GREEN);
    }

    @ParameterizedTest
    @EnumSource(value = ObJourneyStepStatus.class, names = {"IN_PROGRESS", "WAITING_ON_CLIENT", "DONE"})
    void exactlyAtThresholdIsAmber(ObJourneyStepStatus status) {
        assertThat(ObRagCalculator.forStep(75, status, 75)).isEqualTo(ObRag.AMBER);
    }

    @ParameterizedTest
    @EnumSource(value = ObJourneyStepStatus.class, names = {"IN_PROGRESS", "WAITING_ON_CLIENT", "DONE"})
    void exactlyAtBreachIsRed(ObJourneyStepStatus status) {
        assertThat(ObRagCalculator.forStep(100, status, 75)).isEqualTo(ObRag.RED);
    }

    @Test
    void wellPastBreachIsStillJustRed() {
        assertThat(ObRagCalculator.forStep(250, ObJourneyStepStatus.IN_PROGRESS, 75)).isEqualTo(ObRag.RED);
    }

    @Test
    void defaultThresholdIsSeventyFivePercent() {
        assertThat(ObRagCalculator.forStep(74.9, ObJourneyStepStatus.IN_PROGRESS)).isEqualTo(ObRag.GREEN);
        assertThat(ObRagCalculator.forStep(75, ObJourneyStepStatus.IN_PROGRESS)).isEqualTo(ObRag.AMBER);
        assertThat(ObRagCalculator.DEFAULT_AMBER_THRESHOLD_PERCENT).isEqualTo(75);
    }

    // ── BLOCKED gets promoted early, on purpose ─────────────────────

    @Test
    void blockedBelowThresholdIsStillGreen() {
        // Being blocked is not itself a reason to warn before the threshold —
        // only "blocked AND already past the warn line" is.
        assertThat(ObRagCalculator.forStep(50, ObJourneyStepStatus.BLOCKED, 75)).isEqualTo(ObRag.GREEN);
    }

    @Test
    void blockedPastThresholdIsRedNotAmber() {
        assertThat(ObRagCalculator.forStep(80, ObJourneyStepStatus.BLOCKED, 75)).isEqualTo(ObRag.RED);
    }

    @Test
    void blockedExactlyAtThresholdIsRed() {
        assertThat(ObRagCalculator.forStep(75, ObJourneyStepStatus.BLOCKED, 75)).isEqualTo(ObRag.RED);
    }

    @Test
    void waitingOnClientPastThresholdIsOnlyAmber() {
        // The contrast that proves the Red-early rule is BLOCKED-specific,
        // not "any stalled clock state": WAITING_ON_CLIENT at the identical
        // percentage stays Amber.
        assertThat(ObRagCalculator.forStep(80, ObJourneyStepStatus.WAITING_ON_CLIENT, 75)).isEqualTo(ObRag.AMBER);
    }

    // ── worst-of roll-up, used identically at journey and client level ──

    @Test
    void worstOfEmptyIsNull() {
        assertThat(ObRagCalculator.worstOf(List.of())).isNull();
    }

    @Test
    void worstOfAllNullsIsNull() {
        // The client-level "every journey LOCKED" case: no filtering by gate
        // status needed, the null rags already say "nothing running".
        assertThat(ObRagCalculator.worstOf(nullsOnly())).isNull();
    }

    @Test
    void worstOfIgnoresNullsAmongColours() {
        assertThat(ObRagCalculator.worstOf(withNull(ObRag.GREEN))).isEqualTo(ObRag.GREEN);
    }

    @Test
    void redBeatsEverything() {
        assertThat(ObRagCalculator.worstOf(List.of(ObRag.GREEN, ObRag.RED, ObRag.AMBER))).isEqualTo(ObRag.RED);
    }

    @Test
    void amberBeatsGreenWhenNoRed() {
        assertThat(ObRagCalculator.worstOf(List.of(ObRag.GREEN, ObRag.AMBER, ObRag.GREEN))).isEqualTo(ObRag.AMBER);
    }

    @Test
    void allGreenIsGreen() {
        assertThat(ObRagCalculator.worstOf(List.of(ObRag.GREEN, ObRag.GREEN))).isEqualTo(ObRag.GREEN);
    }

    private static List<ObRag> nullsOnly() {
        List<ObRag> values = new java.util.ArrayList<>();
        values.add(null);
        values.add(null);
        return values;
    }

    private static List<ObRag> withNull(ObRag colour) {
        List<ObRag> values = new java.util.ArrayList<>();
        values.add(null);
        values.add(colour);
        return values;
    }
}
