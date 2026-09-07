package com.edunext.edutrack.api.feature.onboarding.reports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-122 · the health formula, which is shared between the chip and the
 * {@code ?rag=} filter and therefore has to be right in one place only.
 */
class ObStepRagTest {

    private static final Instant STARTED = Instant.parse("2026-09-01T09:00:00Z");
    /** A ten-hour window, so 75% of it lands exactly on the seven-and-a-half-hour mark. */
    private static final Instant DUE = STARTED.plus(Duration.ofHours(10));

    @Test
    void beforeThreeQuartersOfTheWindowIsGreen() {
        assertThat(ObStepRag.of(STARTED, DUE, STARTED.plus(Duration.ofHours(7))))
                .isEqualTo(ObStepRag.GREEN);
    }

    /**
     * The boundary is inclusive, so the warning arrives <em>at</em> 75% rather
     * than after it — {@code ObRag}'s stated intent is that AMBER "arrives
     * before the breach rather than reporting it".
     */
    @Test
    @DisplayName("exactly 75% of the window is already amber")
    void theAmberThresholdIsInclusive() {
        Instant threeQuarters = STARTED.plus(Duration.ofMinutes(450));

        assertThat(ObStepRag.of(STARTED, DUE, threeQuarters)).isEqualTo(ObStepRag.AMBER);
    }

    @Test
    void pastTheDeadlineIsRed() {
        assertThat(ObStepRag.of(STARTED, DUE, DUE.plusSeconds(1))).isEqualTo(ObStepRag.RED);
    }

    /**
     * The deadline instant itself is RED, not AMBER. A TAT of ten hours is met
     * by finishing before the tenth hour ends, and "exactly on time" is the
     * case a breach scanner has to catch rather than warn about.
     */
    @Test
    void theDeadlineInstantIsAlreadyRed() {
        assertThat(ObStepRag.of(STARTED, DUE, DUE)).isEqualTo(ObStepRag.RED);
    }

    /**
     * Null is a real answer: {@code ObRag} is "null where there is nothing to
     * colour". Today that is every step, because C-105's clock is what fills
     * {@code due_at} and it has not merged — calling those GREEN would be a
     * health claim made from no evidence.
     */
    @Test
    void aStepWithNoDeadlineHasNoColour() {
        assertThat(ObStepRag.of(STARTED, null, DUE)).isNull();
    }

    /**
     * A step with a deadline and no start can be RED but never AMBER: there is
     * no elapsed share of a window that has not opened.
     */
    @Test
    void anUnstartedStepIsGreenUntilItsDeadlinePasses() {
        assertThat(ObStepRag.of(null, DUE, STARTED)).isEqualTo(ObStepRag.GREEN);
        assertThat(ObStepRag.of(null, DUE, DUE.plusSeconds(1))).isEqualTo(ObStepRag.RED);
    }

    /**
     * A zero-length or inverted window would divide the arithmetic into
     * nonsense. It cannot be AMBER, because there is no share to take.
     */
    @Test
    void aWindowThatNeverOpenedIsGreenUntilItExpires() {
        assertThat(ObStepRag.of(DUE, DUE, STARTED)).isEqualTo(ObStepRag.GREEN);
    }

    // ── the filter ──────────────────────────────────────────────────────────

    @Test
    void noFilterMatchesEverythingIncludingUncolouredRows() {
        assertThat(ObStepRag.matches(ObStepRag.RED, null)).isTrue();
        assertThat(ObStepRag.matches(null, null)).isTrue();
        assertThat(ObStepRag.matches(null, "  ")).isTrue();
    }

    @Test
    void aFilterMatchesItsOwnColourCaseInsensitively() {
        assertThat(ObStepRag.matches(ObStepRag.AMBER, "amber")).isTrue();
        assertThat(ObStepRag.matches(ObStepRag.AMBER, "RED")).isFalse();
    }

    /**
     * An uncoloured row never satisfies a colour filter, so a filtered view
     * cannot silently include rows nothing could measure.
     */
    @Test
    void anUncolouredRowMatchesNoColourFilter() {
        assertThat(ObStepRag.matches(null, "GREEN")).isFalse();
    }

    /**
     * An unrecognised value narrows to nothing rather than throwing — the rule
     * {@link ObReportFilters} states for this parameter.
     */
    @Test
    void anUnrecognisedFilterValueMatchesNothing() {
        assertThat(ObStepRag.matches(ObStepRag.GREEN, "PURPLE")).isFalse();
    }
}
