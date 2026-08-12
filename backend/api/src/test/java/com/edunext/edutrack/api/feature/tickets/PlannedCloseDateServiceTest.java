package com.edunext.edutrack.api.feature.tickets;

import com.edunext.edutrack.domain.identity.ProjectRepository;
import com.edunext.edutrack.domain.masters.Priority;
import com.edunext.edutrack.domain.masters.PriorityRepository;
import com.edunext.edutrack.domain.masters.SlaPolicy;
import com.edunext.edutrack.domain.masters.SlaPolicyRepository;
import com.edunext.edutrack.domain.masters.TaskType;
import com.edunext.edutrack.domain.masters.TaskTypeRepository;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-012 · the resolution ladder, and the boundary between this class and the
 * working-hours service.
 *
 * <p>The calendar walk itself is {@code WorkingHoursServiceTest}'s subject and
 * is not restated here. What <em>is</em> tested is that this class hands the
 * walk every argument that changes its answer — project and assignee — because
 * dropping one of those is silent: the date still looks plausible, it is just
 * computed against the wrong holidays or the wrong person's leave.
 */
class PlannedCloseDateServiceTest {

    private static final long PROJECT = 1L;
    private static final int TASK_TYPE = 7;
    private static final String LEVEL = "HIGH";
    private static final Instant FRIDAY_1800 = Instant.parse("2026-08-14T12:30:00Z");
    private static final Instant COMPUTED = Instant.parse("2026-08-17T05:00:00Z");

    private final SlaPolicyRepository policies = mock(SlaPolicyRepository.class);
    private final PriorityRepository priorities = mock(PriorityRepository.class);
    private final TaskTypeRepository taskTypes = mock(TaskTypeRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final WorkingHoursService workingHours = mock(WorkingHoursService.class);

    private final PlannedCloseDateService service =
            new PlannedCloseDateService(policies, priorities, taskTypes, projects, workingHours);

    @BeforeEach
    void noPoliciesAndNoDefaultsUnlessATestAddsOne() {
        when(policies.findByProjectIdAndTaskTypeIdAndLevelAndIsActiveTrue(anyLong(), anyInt(), anyString()))
                .thenReturn(Optional.empty());
        when(policies.findByProjectIdAndTaskTypeIdIsNullAndLevelAndIsActiveTrueOrderByIdAsc(anyLong(), anyString()))
                .thenReturn(List.of());
        when(policies.findByProjectIdIsNullAndTaskTypeIdIsNullAndLevelAndIsActiveTrueOrderByIdAsc(anyString()))
                .thenReturn(List.of());
        when(priorities.findByCode(anyString())).thenReturn(Optional.of(priority(null)));
        when(taskTypes.findById(anyInt())).thenReturn(Optional.empty());
        when(projects.existsById(anyLong())).thenReturn(true);
        when(workingHours.addWorkingHours(any(), any(), any(), any())).thenReturn(COMPUTED);
    }

    private static SlaPolicy policy(long id, BigDecimal responseHrs, BigDecimal resolutionHrs) {
        SlaPolicy p = new SlaPolicy();
        p.setId(id);
        p.setLevel(LEVEL);
        p.setResponseHrs(responseHrs);
        p.setResolutionHrs(resolutionHrs);
        return p;
    }

    private static Priority priority(BigDecimal defaultSlaHours) {
        Priority p = new Priority();
        p.setCode(LEVEL);
        p.setDefaultSlaHours(defaultSlaHours);
        return p;
    }

    private static TaskType taskType(BigDecimal defaultSlaHours) {
        TaskType t = new TaskType();
        t.setDefaultSlaHours(defaultSlaHours);
        return t;
    }

    @Nested
    @DisplayName("the resolution ladder stops at the first rung that answers")
    class Ladder {

        @Test
        @DisplayName("rung 1 — this project's policy for this task type and level")
        void exactPolicyWins() {
            when(policies.findByProjectIdAndTaskTypeIdAndLevelAndIsActiveTrue(PROJECT, TASK_TYPE, LEVEL))
                    .thenReturn(Optional.of(policy(11L, BigDecimal.valueOf(4), BigDecimal.valueOf(16))));

            SlaResolution sla = service.resolve(PROJECT, TASK_TYPE, LEVEL);

            assertThat(sla.source()).isEqualTo(SlaResolution.Source.PROJECT_TASK_TYPE);
            assertThat(sla.slaPolicyId()).isEqualTo(11L);
            assertThat(sla.resolutionHrs()).isEqualByComparingTo("16");
            assertThat(sla.responseHrs()).isEqualByComparingTo("4");
        }

        @Test
        @DisplayName("rung 2 — the project's default for the level when no task type matches")
        void projectLevelDefault() {
            when(policies.findByProjectIdAndTaskTypeIdIsNullAndLevelAndIsActiveTrueOrderByIdAsc(PROJECT, LEVEL))
                    .thenReturn(List.of(policy(22L, null, BigDecimal.valueOf(24))));

            SlaResolution sla = service.resolve(PROJECT, TASK_TYPE, LEVEL);

            assertThat(sla.source()).isEqualTo(SlaResolution.Source.PROJECT_LEVEL);
            assertThat(sla.slaPolicyId()).isEqualTo(22L);
        }

        @Test
        @DisplayName("rung 3 — the org-wide default")
        void orgDefault() {
            when(policies.findByProjectIdIsNullAndTaskTypeIdIsNullAndLevelAndIsActiveTrueOrderByIdAsc(LEVEL))
                    .thenReturn(List.of(policy(33L, null, BigDecimal.valueOf(48))));

            SlaResolution sla = service.resolve(PROJECT, TASK_TYPE, LEVEL);

            assertThat(sla.source()).isEqualTo(SlaResolution.Source.ORG_DEFAULT);
        }

        /**
         * Rungs 2 and 3 return lists because MySQL treats NULLs in a unique index
         * as distinct, so {@code uq_sla_policies} does not constrain them —
         * {@code SlaPolicyRepository}'s own javadoc. Ordering by id makes "first
         * wins" at least stable across scans, and this pins that we take the
         * first rather than, say, the shortest.
         */
        @Test
        @DisplayName("a rung with several rows takes the lowest id, matching the scanner")
        void ambiguousRungTakesTheFirstRow() {
            when(policies.findByProjectIdAndTaskTypeIdIsNullAndLevelAndIsActiveTrueOrderByIdAsc(PROJECT, LEVEL))
                    .thenReturn(List.of(
                            policy(22L, null, BigDecimal.valueOf(24)),
                            policy(23L, null, BigDecimal.valueOf(8))));

            assertThat(service.resolve(PROJECT, TASK_TYPE, LEVEL).slaPolicyId()).isEqualTo(22L);
        }

        @Test
        @DisplayName("rung 4 — the priority master's default, which still varies with the level")
        void priorityDefault() {
            when(priorities.findByCode(LEVEL)).thenReturn(Optional.of(priority(BigDecimal.valueOf(16))));

            SlaResolution sla = service.resolve(PROJECT, TASK_TYPE, LEVEL);

            assertThat(sla.source()).isEqualTo(SlaResolution.Source.PRIORITY_DEFAULT);
            assertThat(sla.resolutionHrs()).isEqualByComparingTo("16");
            assertThat(sla.slaPolicyId()).isNull();
        }

        @Test
        @DisplayName("rung 5 — the task type's default, only once the level has nothing to say")
        void taskTypeDefault() {
            when(taskTypes.findById(TASK_TYPE)).thenReturn(Optional.of(taskType(BigDecimal.valueOf(72))));

            SlaResolution sla = service.resolve(PROJECT, TASK_TYPE, LEVEL);

            assertThat(sla.source()).isEqualTo(SlaResolution.Source.TASK_TYPE_DEFAULT);
            assertThat(sla.resolutionHrs()).isEqualByComparingTo("72");
        }

        /**
         * The ordering that matters most in this class. A type default applied
         * ahead of a level default hands Critical and Low the same date, which is
         * exactly the behaviour §4B.1 wants the level dropdown to remove — and it
         * would look like a working feature, because a date still appears.
         */
        @Test
        @DisplayName("the priority default beats the task type default when both exist")
        void levelDefaultBeatsTypeDefault() {
            when(priorities.findByCode(LEVEL)).thenReturn(Optional.of(priority(BigDecimal.valueOf(16))));
            when(taskTypes.findById(TASK_TYPE)).thenReturn(Optional.of(taskType(BigDecimal.valueOf(72))));

            assertThat(service.resolve(PROJECT, TASK_TYPE, LEVEL).resolutionHrs()).isEqualByComparingTo("16");
        }

        @Test
        @DisplayName("nothing at all matched is NONE, not a zero")
        void nothingMatches() {
            SlaResolution sla = service.resolve(PROJECT, TASK_TYPE, LEVEL);

            assertThat(sla.source()).isEqualTo(SlaResolution.Source.NONE);
            assertThat(sla.resolutionHrs()).isNull();
            assertThat(sla.hasTarget()).isFalse();
        }

        /**
         * Without a task type the exact rung cannot match, so it must not be
         * queried at all — a repository call with a null key is either a crash or
         * a wrong answer depending on the driver.
         */
        @Test
        @DisplayName("no task type skips rung 1 rather than querying it with a null")
        void noTaskTypeSkipsTheExactRung() {
            service.resolve(PROJECT, null, LEVEL);

            verify(policies, never())
                    .findByProjectIdAndTaskTypeIdAndLevelAndIsActiveTrue(anyLong(), any(), anyString());
        }

        @Test
        @DisplayName("a zero-hour policy is treated as no target, not as due on arrival")
        void zeroHoursIsNotATarget() {
            when(policies.findByProjectIdAndTaskTypeIdAndLevelAndIsActiveTrue(PROJECT, TASK_TYPE, LEVEL))
                    .thenReturn(Optional.of(policy(11L, null, BigDecimal.ZERO)));

            assertThat(service.resolve(PROJECT, TASK_TYPE, LEVEL).hasTarget()).isFalse();
        }
    }

    @Nested
    @DisplayName("the preview hands the calendar everything that changes its answer")
    class CalendarHandoff {

        @BeforeEach
        void anExactPolicy() {
            when(policies.findByProjectIdAndTaskTypeIdAndLevelAndIsActiveTrue(PROJECT, TASK_TYPE, LEVEL))
                    .thenReturn(Optional.of(policy(11L, BigDecimal.valueOf(4), BigDecimal.valueOf(16))));
        }

        @Test
        @DisplayName("the date is the calendar's answer, never computed here")
        void theDateComesFromTheCalendar() {
            PlannedCloseDateService.Preview preview =
                    service.preview(PROJECT, TASK_TYPE, LEVEL, 3L, FRIDAY_1800);

            assertThat(preview.plannedCloseDate()).isEqualTo(COMPUTED);
            verify(workingHours).addWorkingHours(FRIDAY_1800, BigDecimal.valueOf(16), PROJECT, 3L);
        }

        /**
         * Both are optional on the walk and both change the answer — project
         * holidays and that resource's approved leave. Dropping either is the
         * failure mode this test exists for: the date still looks right.
         */
        @Test
        @DisplayName("project and assignee are passed through, not dropped")
        void projectAndAssigneeReachTheWalk() {
            service.preview(PROJECT, TASK_TYPE, LEVEL, 9L, FRIDAY_1800);

            verify(workingHours).addWorkingHours(any(), eq(BigDecimal.valueOf(16)), eq(PROJECT), eq(9L));
        }

        @Test
        @DisplayName("an unassigned ticket honours the org and project calendar alone")
        void noAssigneeIsANullNotAGuess() {
            service.preview(PROJECT, TASK_TYPE, LEVEL, null, FRIDAY_1800);

            verify(workingHours).addWorkingHours(any(), eq(BigDecimal.valueOf(16)), eq(PROJECT), isNull());
        }

        @Test
        @DisplayName("the response target is walked separately, not derived from the resolution one")
        void responseDueIsItsOwnWalk() {
            service.preview(PROJECT, TASK_TYPE, LEVEL, null, FRIDAY_1800);

            verify(workingHours).addWorkingHours(FRIDAY_1800, BigDecimal.valueOf(4), PROJECT, null);
        }

        @Test
        @DisplayName("`from` is echoed so a caller can check which now it was measured from")
        void fromIsEchoed() {
            assertThat(service.preview(PROJECT, TASK_TYPE, LEVEL, null, FRIDAY_1800).from())
                    .isEqualTo(FRIDAY_1800);
        }

        @Test
        @DisplayName("a null `from` means now")
        void nullFromMeansNow() {
            Instant before = Instant.now();

            Instant from = service.preview(PROJECT, TASK_TYPE, LEVEL, null, null).from();

            assertThat(from).isBetween(before, Instant.now());
        }
    }

    @Nested
    @DisplayName("what the preview refuses, and what it answers with nothing")
    class Failures {

        @Test
        @DisplayName("no rung answered gives null dates and NONE — never a fabricated date")
        void noTargetGivesNoDate() {
            PlannedCloseDateService.Preview preview =
                    service.preview(PROJECT, TASK_TYPE, LEVEL, null, FRIDAY_1800);

            assertThat(preview.plannedCloseDate()).isNull();
            assertThat(preview.firstResponseDue()).isNull();
            assertThat(preview.from()).isEqualTo(FRIDAY_1800);
            verify(workingHours, never()).addWorkingHours(any(), any(), any(), any());
        }

        /**
         * A master default carries one figure. Deriving a response target from it
         * — a quarter of resolution, say — would put a commitment on the screen
         * that no administrator ever typed.
         */
        @Test
        @DisplayName("a master default sets no response target")
        void masterDefaultHasNoResponseTarget() {
            when(priorities.findByCode(LEVEL)).thenReturn(Optional.of(priority(BigDecimal.valueOf(16))));

            PlannedCloseDateService.Preview preview =
                    service.preview(PROJECT, TASK_TYPE, LEVEL, null, FRIDAY_1800);

            assertThat(preview.plannedCloseDate()).isEqualTo(COMPUTED);
            assertThat(preview.firstResponseDue()).isNull();
        }

        @Test
        @DisplayName("an unknown project is refused, not answered with the org-wide default")
        void unknownProjectIsRefused() {
            when(projects.existsById(404L)).thenReturn(false);

            assertThatThrownBy(() -> service.preview(404L, TASK_TYPE, LEVEL, null, FRIDAY_1800))
                    .isInstanceOf(UnknownProjectException.class);
        }

        @Test
        @DisplayName("an unknown level is a 400, not a silent no-SLA")
        void unknownLevelIsRefused() {
            when(priorities.findByCode("URGENT")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.preview(PROJECT, TASK_TYPE, "URGENT", null, FRIDAY_1800))
                    .isInstanceOf(UnknownLevelException.class);
        }

        @Test
        @DisplayName("a blank level is refused before any repository is touched")
        void blankLevelIsRefused() {
            assertThatThrownBy(() -> service.preview(PROJECT, TASK_TYPE, "  ", null, FRIDAY_1800))
                    .isInstanceOf(UnknownLevelException.class);
        }
    }
}
