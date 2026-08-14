package com.edunext.edutrack.api.feature.masters.projects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-018 · the SLA tab's decisions, against a mocked repository.
 *
 * <p>Two halves, and the second is the one worth writing. {@link Ladder} is the
 * §6 resolution order, which is the thing this feature exists to render; the
 * refusals and the replace semantics are the thing it exists not to get wrong.
 *
 * <p>{@code SlaMatrixIT} proves the same behaviours against real MySQL, and
 * {@code SlaLadderAgreementTest} proves this ladder answers what C-012's answers
 * — neither of which a mock can settle.
 */
class SlaMatrixServiceTest {

    private static final long PROJECT = 7L;
    private static final long OTHER_PROJECT = 8L;

    private SlaMatrixRepository repository;
    private SlaMatrixService service;

    @BeforeEach
    void setUp() {
        repository = mock(SlaMatrixRepository.class);
        service = new SlaMatrixService(repository);

        when(repository.projectExists(PROJECT)).thenReturn(true);
        when(repository.taskTypeExists(anyInt())).thenReturn(true);
        when(repository.levelExists(anyString())).thenReturn(true);
        when(repository.activeTaskTypes()).thenReturn(List.of(
                taskType(2, "PROD_BUG", "Production Bug", null),
                taskType(4, "FUTURE_RELEASE", "Future Release", hrs(200))));
        when(repository.activeLevels()).thenReturn(List.of(
                level("HIGH", hrs(16)),
                level("CRITICAL", null)));
        when(repository.policiesFor(PROJECT)).thenReturn(List.of());
    }

    // ------------------------------------------------------------------
    // the ladder
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the §6 ladder")
    class Ladder {

        @Test
        @DisplayName("rung 1 — this project's own row for this task type wins over everything below it")
        void projectAndTaskTypeWins() {
            when(repository.policiesFor(PROJECT)).thenReturn(List.of(
                    policy(1, null, null, "HIGH", hrs(4), hrs(24)),        // org-wide
                    policy(2, PROJECT, null, "HIGH", hrs(3), hrs(12)),     // project default
                    policy(3, PROJECT, 2, "HIGH", hrs(1), hrs(6))));       // this cell

            SlaPolicyDtos.SlaCell cell = cell(service.matrix(PROJECT), 2, "HIGH");

            assertThat(cell.source()).isEqualTo(SlaPolicyDtos.Source.PROJECT_TASK_TYPE);
            assertThat(cell.resolutionHrs()).isEqualByComparingTo(hrs(6));
            assertThat(cell.isOverride()).isTrue();
        }

        @Test
        @DisplayName("rung 2 — a project-level row answers every task type it is not overridden for")
        void projectLevelAnswersTheRest() {
            when(repository.policiesFor(PROJECT)).thenReturn(List.of(
                    policy(1, null, null, "HIGH", hrs(4), hrs(24)),
                    policy(2, PROJECT, null, "HIGH", hrs(3), hrs(12)),
                    policy(3, PROJECT, 2, "HIGH", hrs(1), hrs(6))));

            SlaPolicyDtos.SlaCell cell = cell(service.matrix(PROJECT), 4, "HIGH");

            assertThat(cell.source()).isEqualTo(SlaPolicyDtos.Source.PROJECT_LEVEL);
            assertThat(cell.resolutionHrs()).isEqualByComparingTo(hrs(12));
            // The point of the flag: this cell shows a figure and is not one
            // this project's grid wrote, so the PUT must not send it back.
            assertThat(cell.isOverride()).isFalse();
        }

        @Test
        @DisplayName("rung 3 — the org-wide default, and only where task_type_id is also null")
        void orgDefaultAnswers() {
            when(repository.policiesFor(PROJECT)).thenReturn(List.of(
                    policy(1, null, null, "HIGH", hrs(4), hrs(24))));

            SlaPolicyDtos.SlaCell cell = cell(service.matrix(PROJECT), 2, "HIGH");

            assertThat(cell.source()).isEqualTo(SlaPolicyDtos.Source.ORG_DEFAULT);
            assertThat(cell.resolutionHrs()).isEqualByComparingTo(hrs(24));
            assertThat(cell.responseHrs()).isEqualByComparingTo(hrs(4));
        }

        @Test
        @DisplayName("an org-wide row that names a task type answers nothing — §6 has no rung for it")
        void anOrgWideRowWithATaskTypeIsNotARung() {
            // The table permits (null project, non-null task type) and the
            // ladder has no rung for it, so SlaPolicyRepository's rung 3
            // requires task_type_id IS NULL. Ranking it here anyway would make
            // this screen quote a figure C-012's preview does not.
            when(repository.policiesFor(PROJECT)).thenReturn(List.of(
                    policy(1, null, 2, "HIGH", hrs(1), hrs(2))));

            SlaPolicyDtos.SlaCell cell = cell(service.matrix(PROJECT), 2, "HIGH");

            assertThat(cell.source()).isEqualTo(SlaPolicyDtos.Source.PRIORITY_DEFAULT);
            assertThat(cell.resolutionHrs()).isEqualByComparingTo(hrs(16));
        }

        @Test
        @DisplayName("rung 4 — the priority master's default, before the task type's")
        void priorityDefaultOutranksTaskTypeDefault() {
            // Both masters have a figure for this cell. The priority one wins:
            // the three policy rungs are all keyed by level, so the last figure
            // that still varies with the level is the right fallback — a task
            // type default would hand Critical and Low the same date.
            SlaPolicyDtos.SlaCell cell = cell(service.matrix(PROJECT), 4, "HIGH");

            assertThat(cell.source()).isEqualTo(SlaPolicyDtos.Source.PRIORITY_DEFAULT);
            assertThat(cell.resolutionHrs()).isEqualByComparingTo(hrs(16));
        }

        @Test
        @DisplayName("rung 5 — the task type master's default, where the level has none")
        void taskTypeDefaultIsTheLastRung() {
            SlaPolicyDtos.SlaCell cell = cell(service.matrix(PROJECT), 4, "CRITICAL");

            assertThat(cell.source()).isEqualTo(SlaPolicyDtos.Source.TASK_TYPE_DEFAULT);
            assertThat(cell.resolutionHrs()).isEqualByComparingTo(hrs(200));
        }

        @Test
        @DisplayName("NONE carries a null figure rather than a zero — the two are not the same claim")
        void nothingAnswers() {
            // Zero would render as "due immediately" and would take the cell out
            // of SlaResolution.hasTarget() at the same time. Null is the honest
            // answer: this product has nothing to say about this cell.
            SlaPolicyDtos.SlaCell cell = cell(service.matrix(PROJECT), 2, "CRITICAL");

            assertThat(cell.source()).isEqualTo(SlaPolicyDtos.Source.NONE);
            assertThat(cell.resolutionHrs()).isNull();
        }

        @Test
        @DisplayName("a master default carries no response target and A-007's escalation defaults")
        void aDefaultInventsNothing() {
            SlaPolicyDtos.SlaCell cell = cell(service.matrix(PROJECT), 4, "HIGH");

            // Inventing a response target as a fraction of the resolution one
            // would put a number on the screen no administrator ever typed.
            assertThat(cell.responseHrs()).isNull();
            // A-007's column defaults, which is also EscalationPolicies'
            // DEFAULT_POLICY: L1 on, L2 off. L2 stays a decision an
            // organisation makes rather than one it receives.
            assertThat(cell.escalateToL1()).isTrue();
            assertThat(cell.escalateToL2()).isFalse();
        }

        @Test
        @DisplayName("another project's rows never leak into this grid")
        void anotherProjectsRowsAreNotARung() {
            // policiesFor() filters in SQL; this asserts the in-memory index
            // agrees, because the two could drift and the failure would be a
            // project quietly inheriting another's negotiated SLA.
            when(repository.policiesFor(PROJECT)).thenReturn(List.of(
                    policy(1, OTHER_PROJECT, 2, "HIGH", hrs(1), hrs(2)),
                    policy(2, OTHER_PROJECT, null, "HIGH", hrs(1), hrs(3))));

            SlaPolicyDtos.SlaCell cell = cell(service.matrix(PROJECT), 2, "HIGH");

            assertThat(cell.source()).isEqualTo(SlaPolicyDtos.Source.PRIORITY_DEFAULT);
        }

        @Test
        @DisplayName("where a rung has two candidate rows, the lowest id wins — stably")
        void firstWinsByIdNotByChance() {
            // uq_sla_policies cannot make rungs 2 and 3 unique: MySQL treats
            // NULLs in a unique index as distinct. SlaPolicyRepository settles
            // it with ORDER BY id ... LIMIT 1, and this index has to pick the
            // same row or the grid and the planned close date disagree.
            when(repository.policiesFor(PROJECT)).thenReturn(List.of(
                    policy(1, null, null, "HIGH", hrs(4), hrs(24)),
                    policy(2, null, null, "HIGH", hrs(9), hrs(99))));

            SlaPolicyDtos.SlaCell cell = cell(service.matrix(PROJECT), 2, "HIGH");

            assertThat(cell.resolutionHrs()).isEqualByComparingTo(hrs(24));
        }
    }

    // ------------------------------------------------------------------
    // the grid
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the grid")
    class Grid {

        @Test
        @DisplayName("is exhaustive — every active task type × every active level")
        void isExhaustive() {
            // A response carrying only this project's overrides would render as
            // a nearly empty matrix for a project whose tickets all get
            // perfectly good planned close dates.
            assertThat(service.matrix(PROJECT)).hasSize(2 * 2);
        }

        @Test
        @DisplayName("is ordered by the masters' own seq, not by id")
        void isOrderedByMasterSequence() {
            // Insertion order means nothing to an administrator. The repository
            // orders both master reads by seq and this preserves it.
            List<SlaPolicyDtos.SlaCell> cells = service.matrix(PROJECT);

            assertThat(cells).extracting(c -> c.taskTypeCode() + "/" + c.level())
                    .containsExactly(
                            "PROD_BUG/HIGH", "PROD_BUG/CRITICAL",
                            "FUTURE_RELEASE/HIGH", "FUTURE_RELEASE/CRITICAL");
        }

        @Test
        @DisplayName("carries the task type's name, because there is no mounted task-type master yet")
        void carriesTheTaskTypeName() {
            // B-020 has not landed, so nothing else can label these rows. A
            // matrix that renders "task type 4" is not a screen.
            assertThat(service.matrix(PROJECT))
                    .allSatisfy(c -> assertThat(c.taskTypeName()).isNotBlank());
        }

        @Test
        @DisplayName("an unknown project is 404, never an empty grid")
        void anUnknownProjectIsNotAnEmptyGrid() {
            // An empty array for a project that does not exist is a plausible
            // and wrong answer: the tab would render "nothing configured" for a
            // URL somebody mistyped.
            assertThatThrownBy(() -> service.matrix(999L))
                    .isInstanceOf(SlaMatrixService.NoSuchProjectException.class);
        }
    }

    // ------------------------------------------------------------------
    // the replace
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the replace")
    class Replace {

        @Test
        @DisplayName("deactivates this project's overrides before writing the new ones")
        void deactivatesFirst() {
            service.replace(PROJECT, List.of(write(2, "HIGH", hrs(1), hrs(6))));

            verify(repository).deactivateOverrides(PROJECT);
            verify(repository).upsertOverride(PROJECT, 2, "HIGH", hrs(1), hrs(6), true, false);
        }

        @Test
        @DisplayName("an empty body clears every override — it is not treated as a probable mistake")
        void anEmptyBodyClearsEverything() {
            // The alternative is a screen where the last override can be edited
            // but never removed.
            service.replace(PROJECT, List.of());

            verify(repository).deactivateOverrides(PROJECT);
            verify(repository, never()).upsertOverride(
                    anyLong(), anyInt(), anyString(), any(), any(), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName("returns the newly resolved grid, not the body it was sent")
        void returnsTheResolvedGrid() {
            // The two differ wherever a cell was cleared: the caller sent
            // nothing for it and the answer is whatever it now inherits.
            List<SlaPolicyDtos.SlaCell> after = service.replace(PROJECT, List.of());

            assertThat(after).hasSize(4);
            assertThat(after).noneMatch(SlaPolicyDtos.SlaCell::isOverride);
        }

        @Test
        @DisplayName("omitted escalation flags take A-007's defaults, not Java's false")
        void omittedFlagsTakeTheSchemaDefaults() {
            // Boxed for exactly this: unboxed, an omitted escalateToL1 would
            // arrive as false and quietly switch L1 escalation off for every
            // cell a client sent without the field — the opposite of the
            // documented default.
            service.replace(PROJECT, List.of(
                    new SlaPolicyDtos.SlaPolicyWrite(2, "HIGH", null, hrs(6), null, null)));

            verify(repository).upsertOverride(PROJECT, 2, "HIGH", null, hrs(6), true, false);
        }

        @Test
        @DisplayName("writes nothing at all when any row is refused")
        void refusesTheWholeBody() {
            // One transaction, so a row refused halfway through would roll back
            // the rows before it and the caller would be told about the twelfth
            // cell of a save that also silently did not apply the first eleven.
            when(repository.levelExists("URGENT")).thenReturn(false);

            assertThatThrownBy(() -> service.replace(PROJECT, List.of(
                    write(2, "HIGH", hrs(1), hrs(6)),
                    write(4, "URGENT", hrs(1), hrs(6)))))
                    .isInstanceOf(SlaMatrixService.SlaValidationException.class);

            verify(repository, never()).deactivateOverrides(anyLong());
            verify(repository, never()).upsertOverride(
                    anyLong(), anyInt(), anyString(), any(), any(), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName("an unknown project is refused before anything is written")
        void anUnknownProjectWritesNothing() {
            assertThatThrownBy(() -> service.replace(999L, List.of(write(2, "HIGH", hrs(1), hrs(6)))))
                    .isInstanceOf(SlaMatrixService.NoSuchProjectException.class);

            verify(repository, never()).deactivateOverrides(anyLong());
        }
    }

    // ------------------------------------------------------------------
    // the refusals
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the refusals")
    class Refusals {

        @Test
        @DisplayName("the same cell twice — which the unique key cannot catch")
        void theSameCellTwice() {
            // Both rows have the same (project, task type, level), so
            // uq_sla_policies sees one key and ON DUPLICATE KEY UPDATE keeps
            // the last quietly. The caller would be told the save succeeded and
            // one of the two figures they entered would be gone.
            assertThatThrownBy(() -> service.replace(PROJECT, List.of(
                    write(2, "HIGH", hrs(1), hrs(6)),
                    write(2, "HIGH", hrs(2), hrs(9)))))
                    .isInstanceOf(SlaMatrixService.SlaValidationException.class)
                    .hasMessageContaining("twice")
                    .satisfies(e -> assertThat(field(e)).isEqualTo("taskTypeId"));
        }

        @Test
        @DisplayName("the same task type at two different levels is fine")
        void twoLevelsOfOneTaskTypeIsNotADuplicate() {
            service.replace(PROJECT, List.of(
                    write(2, "HIGH", hrs(1), hrs(6)),
                    write(2, "CRITICAL", hrs(1), hrs(2))));

            verify(repository, times(2)).upsertOverride(
                    eq(PROJECT), eq(2), anyString(), any(), any(), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName("an unknown task type, keyed on the field so the message lands on the cell")
        void anUnknownTaskType() {
            when(repository.taskTypeExists(99)).thenReturn(false);

            assertThatThrownBy(() -> service.replace(PROJECT, List.of(write(99, "HIGH", hrs(1), hrs(6)))))
                    .isInstanceOf(SlaMatrixService.SlaValidationException.class)
                    .satisfies(e -> assertThat(field(e)).isEqualTo("taskTypeId"));
        }

        @Test
        @DisplayName("an unknown level — checked against the priority master, not against an enum")
        void anUnknownLevel() {
            // S-12 lets an Admin add a level without a release, which is why
            // tickets.level is a VARCHAR and not a foreign key. A Java enum here
            // would make the fifth level a code change.
            when(repository.levelExists("URGENT")).thenReturn(false);

            assertThatThrownBy(() -> service.replace(PROJECT, List.of(write(2, "URGENT", hrs(1), hrs(6)))))
                    .isInstanceOf(SlaMatrixService.SlaValidationException.class)
                    .satisfies(e -> assertThat(field(e)).isEqualTo("level"));
        }

        @Test
        @DisplayName("a response target longer than the resolution target — a transposition, not a policy")
        void responseLongerThanResolution() {
            // Nothing downstream would reject it: the scanner would warn about a
            // first response that is overdue after the ticket was already due to
            // be closed.
            assertThatThrownBy(() -> service.replace(PROJECT, List.of(write(2, "HIGH", hrs(9), hrs(6)))))
                    .isInstanceOf(SlaMatrixService.SlaValidationException.class)
                    .satisfies(e -> assertThat(field(e)).isEqualTo("responseHrs"));
        }

        @Test
        @DisplayName("equal response and resolution targets are allowed")
        void equalTargetsAreAllowed() {
            // "Respond and resolve within the hour" is a real commitment, and
            // the rule is about ordering, not about margin.
            service.replace(PROJECT, List.of(write(2, "HIGH", hrs(6), hrs(6))));

            verify(repository).upsertOverride(PROJECT, 2, "HIGH", hrs(6), hrs(6), true, false);
        }

        @Test
        @DisplayName("a deactivated task type is not refused — its override must stay editable")
        void aDeactivatedTaskTypeIsNotRefused() {
            // taskTypeExists checks existence, not activity. An override on a
            // retired type is configuration somebody entered while it was live;
            // refusing it would make the row uneditable and unremovable through
            // the only screen that can see it.
            when(repository.taskTypeExists(2)).thenReturn(true);
            when(repository.activeTaskTypes()).thenReturn(List.of(
                    taskType(4, "FUTURE_RELEASE", "Future Release", hrs(200))));

            service.replace(PROJECT, List.of(write(2, "HIGH", hrs(1), hrs(6))));

            verify(repository).upsertOverride(PROJECT, 2, "HIGH", hrs(1), hrs(6), true, false);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static SlaPolicyDtos.SlaCell cell(List<SlaPolicyDtos.SlaCell> cells, int taskTypeId, String level) {
        return cells.stream()
                .filter(c -> c.taskTypeId() == taskTypeId && c.level().equals(level))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no cell for " + taskTypeId + "/" + level));
    }

    private static String field(Throwable e) {
        return ((SlaMatrixService.SlaValidationException) e).field();
    }

    private static SlaPolicyDtos.SlaPolicyWrite write(int taskTypeId, String level,
                                                      BigDecimal responseHrs, BigDecimal resolutionHrs) {
        return new SlaPolicyDtos.SlaPolicyWrite(taskTypeId, level, responseHrs, resolutionHrs, true, false);
    }

    private static SlaMatrixRepository.TaskTypeRow taskType(int id, String code, String name, BigDecimal defaultHrs) {
        return new SlaMatrixRepository.TaskTypeRow(id, code, name, defaultHrs);
    }

    private static SlaMatrixRepository.LevelRow level(String code, BigDecimal defaultHrs) {
        return new SlaMatrixRepository.LevelRow(code, defaultHrs);
    }

    private static SlaMatrixRepository.PolicyRow policy(long id, Long projectId, Integer taskTypeId,
                                                        String level, BigDecimal responseHrs,
                                                        BigDecimal resolutionHrs) {
        return new SlaMatrixRepository.PolicyRow(
                id, projectId, taskTypeId, level, responseHrs, resolutionHrs, true, false);
    }

    private static BigDecimal hrs(double value) {
        return BigDecimal.valueOf(value);
    }
}
