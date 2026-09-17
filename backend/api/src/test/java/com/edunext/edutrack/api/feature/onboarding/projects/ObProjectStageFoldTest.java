package com.edunext.edutrack.api.feature.onboarding.projects;

import com.edunext.edutrack.api.feature.onboarding.projects.ObProjectReadRepository.StageRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fold behind every project-level stage figure on the screen.
 *
 * <h2>Why this is asserted on its own</h2>
 *
 * <p>{@code STAGE_ROLLUP} is grouped by journey so the project page's tree can
 * give each Module Service its own stages. Everything that speaks for the
 * project as a whole — the grid's "Stages 2/7", the header, the current-stage
 * name — reads the same rows summed back up, and that sum is the one place the
 * regrouping could silently change what every existing screen reports.
 *
 * <p>It is a pure static over a record, so it is asserted here rather than
 * through a container. The rule these enforce is that folding journey-grain
 * rows reproduces exactly what the old project-grain {@code GROUP BY} returned.
 */
class ObProjectStageFoldTest {

    private static final long PROJECT = 7L;
    private static final long SIS = 500L;
    private static final long ATTENDANCE = 501L;

    private static StageRow row(long journeyId, long stageKey, String name, int sequence,
                                int taskCount, int outstanding, Integer minActive) {
        return new StageRow(PROJECT, journeyId, stageKey, name, sequence, taskCount, outstanding,
                minActive);
    }

    @Test
    @DisplayName("two services' rows for one stage become one stage")
    void foldsTheSameStageAcrossServices() {
        List<StageRow> folded = ObProjectService.foldToProject(List.of(
                row(SIS, 1L, "Configuration", 1, 1, 0, null),
                row(ATTENDANCE, 1L, "Configuration", 1, 2, 2, null)));

        assertThat(folded).hasSize(1);
        assertThat(folded.getFirst().stageKey()).isEqualTo(1L);
        assertThat(folded.getFirst().taskCount()).isEqualTo(3);
        assertThat(folded.getFirst().tasksOutstanding()).isEqualTo(2);
    }

    /**
     * A project with two services through a six-stage master must still report
     * six stages. Counting them per service is what would tell a reader it has
     * twelve.
     */
    @Test
    @DisplayName("the stage count is the master's, not the master's times the services")
    void doesNotMultiplyStagesByServices() {
        List<StageRow> folded = ObProjectService.foldToProject(List.of(
                row(SIS, 1L, "Configuration", 1, 1, 0, null),
                row(SIS, 2L, "Data Migration", 2, 2, 2, null),
                row(ATTENDANCE, 1L, "Configuration", 1, 2, 2, null),
                row(ATTENDANCE, 2L, "Data Migration", 2, 1, 1, null)));

        assertThat(folded).hasSize(2);
        assertThat(folded).extracting(StageRow::stageName)
                .containsExactly("Configuration", "Data Migration");
    }

    /**
     * The running stage of a project is the earliest one running in any of its
     * services — the same reading the SQL's own {@code MIN} had when it grouped
     * across journeys.
     */
    @Test
    @DisplayName("the earliest running task wins, whichever service it is in")
    void takesTheEarliestActiveSequence() {
        List<StageRow> folded = ObProjectService.foldToProject(List.of(
                row(SIS, 1L, "Configuration", 1, 3, 2, 5),
                row(ATTENDANCE, 1L, "Configuration", 1, 2, 2, 2)));

        assertThat(folded.getFirst().minActiveSequence()).isEqualTo(2);
    }

    @Test
    @DisplayName("a stage nothing is running in stays null rather than becoming zero")
    void keepsAbsentActivityAbsent() {
        List<StageRow> folded = ObProjectService.foldToProject(List.of(
                row(SIS, 1L, "Configuration", 1, 1, 1, null),
                row(ATTENDANCE, 1L, "Configuration", 1, 1, 1, null)));

        assertThat(folded.getFirst().minActiveSequence()).isNull();
    }

    @Test
    @DisplayName("one service running and one idle reports the running one")
    void oneSidedActivitySurvivesTheFold() {
        List<StageRow> folded = ObProjectService.foldToProject(List.of(
                row(SIS, 1L, "Configuration", 1, 1, 1, null),
                row(ATTENDANCE, 1L, "Configuration", 1, 1, 1, 4)));

        assertThat(folded.getFirst().minActiveSequence()).isEqualTo(4);
    }

    /**
     * An empty stage has nothing outstanding either. Summing must not turn
     * "neither service scheduled this" into something a caller can read as
     * finished — which is why every caller tests {@code taskCount > 0} and why
     * the fold has to preserve the zero rather than drop the row.
     */
    @Test
    @DisplayName("a stage neither service scheduled survives the fold, still empty")
    void keepsAStageNobodyScheduled() {
        List<StageRow> folded = ObProjectService.foldToProject(List.of(
                row(SIS, 4L, "Training", 4, 0, 0, null),
                row(ATTENDANCE, 4L, "Training", 4, 0, 0, null)));

        assertThat(folded).hasSize(1);
        assertThat(folded.getFirst().taskCount()).isZero();
        assertThat(folded.getFirst().tasksOutstanding()).isZero();
    }

    /**
     * A stage one service uses and another does not folds to the one that does.
     * The per-service reading is what keeps that distinction; this asserts the
     * folded reading does not invent work in the service that has none.
     */
    @Test
    @DisplayName("an empty row and a busy row for one stage sum to the busy one")
    void foldsAnEmptyRowIntoABusyOne() {
        List<StageRow> folded = ObProjectService.foldToProject(List.of(
                row(SIS, 3L, "Reports", 3, 1, 1, null),
                row(ATTENDANCE, 3L, "Reports", 3, 0, 0, null)));

        assertThat(folded).hasSize(1);
        assertThat(folded.getFirst().taskCount()).isEqualTo(1);
        assertThat(folded.getFirst().tasksOutstanding()).isEqualTo(1);
    }

    /**
     * The query returns journey by journey, so the rows arrive interleaved
     * rather than in stage order. Callers index the result as the ribbon's own
     * order, so the fold restores it.
     */
    @Test
    @DisplayName("the result is in ribbon order however the rows arrived")
    void ordersBySequenceThenKey() {
        List<StageRow> folded = ObProjectService.foldToProject(List.of(
                row(SIS, 3L, "Reports", 3, 1, 1, null),
                row(SIS, 1L, "Configuration", 1, 1, 0, null),
                row(ATTENDANCE, 0L, "Ungrouped", 9999, 1, 1, null),
                row(ATTENDANCE, 2L, "Data Migration", 2, 1, 1, null)));

        assertThat(folded).extracting(StageRow::stageName)
                .containsExactly("Configuration", "Data Migration", "Reports", "Ungrouped");
    }

    @Test
    @DisplayName("a project with no journey folds to nothing rather than failing")
    void handlesAProjectWithNoRows() {
        assertThat(ObProjectService.foldToProject(List.of())).isEmpty();
    }

    /**
     * A single-service project is the common case, and the fold must be the
     * identity on it — anything else would move figures on every project that
     * bought one module.
     */
    @Test
    @DisplayName("one service folds to exactly what it reported")
    void isTheIdentityForASingleService() {
        List<StageRow> input = List.of(
                row(SIS, 1L, "Configuration", 1, 3, 1, 2),
                row(SIS, 2L, "Data Migration", 2, 0, 0, null));

        List<StageRow> folded = ObProjectService.foldToProject(input);

        assertThat(folded).extracting(StageRow::stageKey, StageRow::taskCount,
                        StageRow::tasksOutstanding, StageRow::minActiveSequence)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1L, 3, 1, 2),
                        org.assertj.core.groups.Tuple.tuple(2L, 0, 0, null));
    }
}
