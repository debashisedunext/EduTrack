package com.edunext.edutrack.api.feature.tickets;

import com.edunext.edutrack.domain.identity.ProjectRepository;
import com.edunext.edutrack.domain.masters.Priority;
import com.edunext.edutrack.domain.masters.PriorityRepository;
import com.edunext.edutrack.domain.masters.SlaPolicy;
import com.edunext.edutrack.domain.masters.SlaPolicyRepository;
import com.edunext.edutrack.domain.masters.TaskType;
import com.edunext.edutrack.domain.masters.TaskTypeRepository;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * C-012 · SLA policy resolution and the planned close date it produces.
 *
 * <p>Two steps, and the split matters. {@link #resolve} answers <em>how long</em>
 * — a figure in working hours out of the §6 ladder. {@link #preview} answers
 * <em>when that lands</em>, and it does so through {@link WorkingHoursService}
 * and nothing else: weekends, org and project holidays and the assignee's
 * approved leave are the calendar's business, not this class's. A Friday 18:00
 * ticket on a 4-hour SLA has to land Monday morning, and the one thing that
 * guarantees every figure in the product agrees about that is that nobody
 * reimplements the walk. B-024's javadoc names this task as its caller.
 *
 * <p><b>This computes; it does not persist.</b> The create path stores whatever
 * it is given, and §7.5's rule that a supplied date requires PM or Admin lives
 * there. The one invariant this class exists to protect is that the date the
 * user was shown before committing and the date the server computes on save
 * come out of the same code — which is why the preview endpoint returns a real
 * computation rather than the form estimating one from the priority master.
 *
 * <p>Changing a policy never moves an existing ticket's date (the contract's own
 * note on {@code replaceSlaPolicies}). Nothing here reads a stored ticket, so
 * that holds by construction rather than by discipline.
 */
@Service
public class PlannedCloseDateService {

    private final SlaPolicyRepository policies;
    private final PriorityRepository priorities;
    private final TaskTypeRepository taskTypes;
    private final ProjectRepository projects;
    private final WorkingHoursService workingHours;

    PlannedCloseDateService(SlaPolicyRepository policies,
                            PriorityRepository priorities,
                            TaskTypeRepository taskTypes,
                            ProjectRepository projects,
                            WorkingHoursService workingHours) {
        this.policies = policies;
        this.priorities = priorities;
        this.taskTypes = taskTypes;
        this.projects = projects;
        this.workingHours = workingHours;
    }

    /**
     * What {@link #preview} answers: the dates, and the resolution that produced
     * them, so a caller can say <em>why</em> as well as <em>when</em>.
     *
     * <p>{@code plannedCloseDate} and {@code firstResponseDue} are independently
     * nullable. A ticket can have a resolution target and no response target —
     * the two master defaults carry only the former — and null means "this
     * product has nothing to say about that", which is a different claim from a
     * date far in the future.
     */
    public record Preview(Instant from,
                          Instant plannedCloseDate,
                          Instant firstResponseDue,
                          SlaResolution sla) {
    }

    /**
     * The §6 ladder, most specific first, stopping at the first rung that
     * answers. See {@link SlaResolution.Source} for why it does not stop at the
     * three policy rungs the repository implements.
     *
     * <p>{@code level} is the {@link Priority} code, matching
     * {@code tickets.level} — the string, not an enum, because S-12 lets an
     * administrator add a level without a release and a Java enum would make
     * that a code change.
     */
    @Transactional(readOnly = true)
    public SlaResolution resolve(Long projectId, Integer taskTypeId, String level) {
        if (level == null || level.isBlank()) {
            return SlaResolution.none();
        }

        if (projectId != null && taskTypeId != null) {
            Optional<SlaPolicy> exact =
                    policies.findByProjectIdAndTaskTypeIdAndLevelAndIsActiveTrue(projectId, taskTypeId, level);
            if (exact.isPresent()) {
                return SlaResolution.of(SlaResolution.Source.PROJECT_TASK_TYPE, exact.get());
            }
        }

        if (projectId != null) {
            Optional<SlaPolicy> projectDefault = first(
                    policies.findByProjectIdAndTaskTypeIdIsNullAndLevelAndIsActiveTrueOrderByIdAsc(projectId, level));
            if (projectDefault.isPresent()) {
                return SlaResolution.of(SlaResolution.Source.PROJECT_LEVEL, projectDefault.get());
            }
        }

        Optional<SlaPolicy> orgDefault = first(
                policies.findByProjectIdIsNullAndTaskTypeIdIsNullAndLevelAndIsActiveTrueOrderByIdAsc(level));
        if (orgDefault.isPresent()) {
            return SlaResolution.of(SlaResolution.Source.ORG_DEFAULT, orgDefault.get());
        }

        Optional<BigDecimal> priorityDefault = priorities.findByCode(level)
                .map(Priority::getDefaultSlaHours)
                .filter(PlannedCloseDateService::isPositive);
        if (priorityDefault.isPresent()) {
            return SlaResolution.fromDefault(SlaResolution.Source.PRIORITY_DEFAULT, priorityDefault.get());
        }

        Optional<BigDecimal> taskTypeDefault = Optional.ofNullable(taskTypeId)
                .flatMap(taskTypes::findById)
                .map(TaskType::getDefaultSlaHours)
                .filter(PlannedCloseDateService::isPositive);
        return taskTypeDefault
                .map(hrs -> SlaResolution.fromDefault(SlaResolution.Source.TASK_TYPE_DEFAULT, hrs))
                .orElseGet(SlaResolution::none);
    }

    /**
     * Where the resolved target lands on the working calendar.
     *
     * <p>{@code from} is the clock start — the date the ticket is reported, not
     * necessarily now: §7.5 lets a PM or Admin backdate that field, and a
     * preview measured from the wrong instant is worse than none. A null
     * {@code from} means now.
     *
     * <p>{@code assigneeId} is optional and is the resource whose approved leave
     * stops the clock. An unassigned ticket honours only the org and project
     * calendar, which is correct rather than a shortcut — nothing yet knows
     * whose leave would apply. It also means the preview <b>moves when the
     * assignee changes</b>, which is the honest answer and worth the extra
     * fetch: promising a date against a person who is on leave all next week is
     * the kind of commitment that gets made to a client.
     *
     * <p>Both arguments that name a row are checked before anything is computed.
     * An unknown project would otherwise fall quietly through to the org-wide
     * default and return a confident date for a project that does not exist, and
     * an unknown level would return no date at all — see
     * {@link UnknownLevelException} for why those two must not look alike.
     *
     * @throws UnknownProjectException 404, never 403 — see that class
     * @throws UnknownLevelException   400; the level is not a priority code
     */
    @Transactional(readOnly = true)
    public Preview preview(Long projectId, Integer taskTypeId, String level, Long assigneeId, Instant from) {
        if (projectId != null && !projects.existsById(projectId)) {
            throw new UnknownProjectException(projectId);
        }
        if (level == null || level.isBlank() || priorities.findByCode(level).isEmpty()) {
            throw new UnknownLevelException(level);
        }

        Instant start = from == null ? Instant.now() : from;
        SlaResolution sla = resolve(projectId, taskTypeId, level);

        if (!sla.hasTarget()) {
            return new Preview(start, null, null, sla);
        }

        Instant plannedClose = workingHours.addWorkingHours(start, sla.resolutionHrs(), projectId, assigneeId);
        Instant responseDue = isPositive(sla.responseHrs())
                ? workingHours.addWorkingHours(start, sla.responseHrs(), projectId, assigneeId)
                : null;
        return new Preview(start, plannedClose, responseDue, sla);
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static boolean isPositive(BigDecimal hours) {
        return hours != null && hours.signum() > 0;
    }
}
