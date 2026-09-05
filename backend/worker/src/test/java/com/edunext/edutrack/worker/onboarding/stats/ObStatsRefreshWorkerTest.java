package com.edunext.edutrack.worker.onboarding.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-120 · the two decisions the scheduler makes on its own.
 *
 * <p>Everything else it does is a delegation, and
 * {@code ObDashboardStatsRepository} owns the arithmetic. What is here is the
 * window clamp — the rule that decides whether a fresh deployment fabricates a
 * week of zeroes — and the property validation that stops a misconfigured amber
 * threshold reaching a dashboard as a plausible-looking colour.
 */
class ObStatsRefreshWorkerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 12);

    @Nested
    @DisplayName("the flow window")
    class FlowWindow {

        @Test
        @DisplayName("a virgin table is refreshed for today alone, never for days before it existed")
        void aVirginTableFillsForwardRatherThanBackwards() {
            // The clamp's whole job. Without it the first pass would write seven
            // flow rows, six of them carrying stock zeroes — and a zero on the
            // RAG board is a claim ("no journeys were open") where an absent row
            // is silence. A-108 chose silence.
            assertThat(ObStatsRefreshWorker.flowWindowStart(TODAY, 7, Optional.empty()))
                    .isEqualTo(TODAY);
        }

        @Test
        @DisplayName("a table younger than the window is refreshed from the day it landed")
        void theWindowWidensAsTheTableAges() {
            LocalDate landed = TODAY.minusDays(2);

            assertThat(ObStatsRefreshWorker.flowWindowStart(TODAY, 7, Optional.of(landed)))
                    .isEqualTo(landed);
        }

        @Test
        @DisplayName("an older table is refreshed for the full window and no further")
        void theWindowStopsWideningOnceItIsFull() {
            assertThat(ObStatsRefreshWorker.flowWindowStart(TODAY, 7, Optional.of(TODAY.minusYears(1))))
                    .isEqualTo(TODAY.minusDays(6));
        }

        @Test
        @DisplayName("a window of one is today, inclusive — the off-by-one this arithmetic invites")
        void aWindowOfOneIsTodayOnly() {
            assertThat(ObStatsRefreshWorker.flowWindowStart(TODAY, 1, Optional.of(TODAY.minusYears(1))))
                    .isEqualTo(TODAY);
        }
    }

    @Nested
    @DisplayName("the properties")
    class Properties {

        @Test
        void aSensibleConfigurationIsAccepted() {
            ObStatsProperties props =
                    new ObStatsProperties(true, Duration.ofMinutes(5), 7, new BigDecimal("0.75"));

            assertThat(props.amberShare()).isEqualByComparingTo("0.75");
            assertThat(props.flowWindowDays()).isEqualTo(7);
        }

        @Test
        @DisplayName("a window of zero days is refused rather than silently refreshing nothing")
        void aZeroWindowIsRefused() {
            assertThatThrownBy(() ->
                    new ObStatsProperties(true, Duration.ofMinutes(5), 0, new BigDecimal("0.75")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("flow-window-days");
        }

        @Test
        @DisplayName("an amber share outside (0, 1) is refused at startup, not discovered on the board")
        void anImpossibleAmberShareIsRefused() {
            // Zero paints every started step amber the instant it starts; one
            // paints none of them until the moment it breaches. In both cases the
            // colour stops carrying information rather than failing visibly,
            // which is the failure worth refusing at startup.
            assertThatThrownBy(() ->
                    new ObStatsProperties(true, Duration.ofMinutes(5), 7, BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("amber-share");
            assertThatThrownBy(() ->
                    new ObStatsProperties(true, Duration.ofMinutes(5), 7, BigDecimal.ONE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("amber-share");
            assertThatThrownBy(() ->
                    new ObStatsProperties(true, Duration.ofMinutes(5), 7, new BigDecimal("-0.1")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() ->
                    new ObStatsProperties(true, Duration.ofMinutes(5), 7, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
