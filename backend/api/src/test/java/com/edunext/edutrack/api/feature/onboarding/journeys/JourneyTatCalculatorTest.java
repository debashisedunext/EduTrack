package com.edunext.edutrack.api.feature.onboarding.journeys;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JourneyTatCalculator} — the rule that replaced {@code sum(tat_days)}
 * everywhere a Module Service's TAT is printed.
 *
 * <p>The case the old figure got wrong is the first test: two tasks that wait
 * for nothing run <em>alongside</em> each other, so their TATs do not add. The
 * sum passed every test that existed, because every test that existed was
 * about a chain.
 *
 * <p>Kept deliberately in step with {@code journeyTemplateTat.test.ts}, which
 * asserts the same five cases against the designer's own walk: the two have to
 * agree, or the catalogue card and the Schedule column on the next screen
 * disagree about the same service.
 */
class JourneyTatCalculatorTest {

    /** A task. {@code dependsOn} null is parallel from day 1, not "first". */
    private static JourneyTatCalculator.Task task(long id, int tatDays, Long dependsOn) {
        return new JourneyTatCalculator.Task(id, tatDays, dependsOn);
    }

    @Nested
    @DisplayName("a dependency adds; a parallel branch does not")
    class TheRule {

        @Test
        @DisplayName("two tasks that wait for nothing take the longer of the two, not the sum")
        void parallelTasksDoNotAdd() {
            assertThat(JourneyTatCalculator.criticalPathDays(List.of(
                    task(1L, 1, null),
                    task(2L, 2, null))))
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the same two tasks chained really are consecutive days")
        void aChainAdds() {
            assertThat(JourneyTatCalculator.criticalPathDays(List.of(
                    task(1L, 1, null),
                    task(2L, 2, 1L))))
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("with several chains at once, the longest one is the answer")
        void theLongestChainWins() {
            // 1→2 is 2+3 = 5 days; 3 alone is 4. Shortening task 3 finishes the
            // service no sooner, which is exactly what a critical path means.
            assertThat(JourneyTatCalculator.criticalPathDays(List.of(
                    task(1L, 2, null),
                    task(2L, 3, 1L),
                    task(3L, 4, null))))
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("every link of a deep chain adds")
        void deepChainsAddThroughout() {
            assertThat(JourneyTatCalculator.criticalPathDays(List.of(
                    task(1L, 2, null),
                    task(2L, 2, 1L),
                    task(3L, 2, 2L))))
                    .isEqualTo(6);
        }

        @Test
        @DisplayName("two tasks hanging off one predecessor both start the day it ends")
        void siblingsShareAStartDay() {
            // Task 1 ends on day 2; both 2 and 3 start on day 3, so the plan
            // ends on day 5 rather than day 8.
            assertThat(JourneyTatCalculator.criticalPathDays(List.of(
                    task(1L, 2, null),
                    task(2L, 3, 1L),
                    task(3L, 1, 1L))))
                    .isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("what it does with data the walk cannot trust")
    class Defensively {

        @Test
        @DisplayName("no tasks is zero — the usual state of a service somebody has just created")
        void emptyIsZero() {
            assertThat(JourneyTatCalculator.criticalPathDays(List.of())).isZero();
        }

        @Test
        @DisplayName("order in the list does not matter — a dependency may be listed before or after its holder")
        void listOrderIsIrrelevant() {
            assertThat(JourneyTatCalculator.criticalPathDays(List.of(
                    task(2L, 2, 1L),
                    task(1L, 1, null))))
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("a dependency on a task that is not in the set starts on day 1 rather than vanishing")
        void anAbsentPredecessorIsARoot() {
            // The composite foreign key keeps a dependency inside its own
            // template, so this is the mid-edit view rather than stored data —
            // and a task dropped out of the total is the one outcome that must
            // not be possible whatever the data does.
            assertThat(JourneyTatCalculator.criticalPathDays(List.of(
                    task(2L, 4, 999L))))
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("a cycle terminates instead of recursing for ever")
        void aCycleTerminates() {
            // The service refuses a cycle on every edit that could create one,
            // which is exactly why the guard is cheap to keep: a stack overflow
            // inside a catalogue read would be a 500 on a page listing every
            // product.
            assertThat(JourneyTatCalculator.criticalPathDays(List.of(
                    task(1L, 2, 2L),
                    task(2L, 3, 1L))))
                    .isPositive();
        }

        @Test
        @DisplayName("a TAT below the column's own minimum counts as one day, not as a backwards range")
        void tatIsFlooredAtOneDay() {
            assertThat(JourneyTatCalculator.criticalPathDays(List.of(
                    task(1L, 0, null),
                    task(2L, 0, 1L))))
                    .isEqualTo(2);
        }
    }
}
