package com.edunext.edutrack.api.feature.onboarding.prereqs;

import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqTask;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqTaskRepository;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqs;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqsRepository;
import com.edunext.edutrack.domain.onboarding.ObPrereqTemplateTask;
import com.edunext.edutrack.domain.onboarding.ObPrereqTemplateVersion;
import com.edunext.edutrack.domain.onboarding.ObStepTatBudget;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * B-125 · a client's own prerequisite checklist: created at boarding by
 * snapshotting the master, and read back by OB-05 and CP-03.
 *
 * <h2>The snapshot is a copy, and that is the whole mechanism</h2>
 *
 * <p>Plan §1.1 #2 makes template snapshotting apply to the prerequisites
 * master by name. {@link #instantiate} copies the active version's wording,
 * TAT and mandatory flag onto rows of this client's own, and pins the version
 * id on the header. Publishing a newer master afterwards leaves this client
 * on the checklist they were actually given — including a client whose gate
 * is still locked, because a task they had already submitted would otherwise
 * be replaced by one they have never seen.
 *
 * <h2>What this class does not do</h2>
 *
 * <p>No transitions — {@link ObPrereqTaskService} owns those, and each has
 * its own reason and its own refusals. No gate evaluation: {@link ObPrereqGate}
 * is the seam and C-118 fills it.
 */
@Service
public class ObClientPrereqService {

    private final ObClientPrereqsRepository headers;
    private final ObClientPrereqTaskRepository tasks;
    private final ObPrereqTemplateService master;
    private final WorkingHoursService workingHours;
    private final WorkingCalendarRepository workingCalendars;

    public ObClientPrereqService(ObClientPrereqsRepository headers,
                                 ObClientPrereqTaskRepository tasks,
                                 ObPrereqTemplateService master,
                                 WorkingHoursService workingHours,
                                 WorkingCalendarRepository workingCalendars) {
        this.headers = headers;
        this.tasks = tasks;
        this.master = master;
        this.workingHours = workingHours;
        this.workingCalendars = workingCalendars;
    }

    /**
     * Board a client onto the active version of the master.
     *
     * <p>Called by OB-04's wizard (B-109) inside the transaction that creates
     * the client, so a client never exists without a checklist. Idempotent by
     * refusal rather than by silence: a second call is a bug in the caller,
     * and returning the existing header would hide it.
     *
     * <p><b>Every task's clock starts now.</b> {@code dueAt} is
     * working-calendar derived from the snapshotted {@code tatDays} at this
     * moment — not at gate-open, and not when the client first logs in. Plan
     * §5.4 attributes prerequisite time to the client from boarding, which is
     * when they were asked.
     *
     * @throws PrereqsAlreadyInstantiatedException the client already has one
     * @throws NoActivePrereqMasterException       nothing published to snapshot
     */
    @Transactional
    public ObClientPrereqs instantiate(long obClientId) {
        if (headers.existsByObClientId(obClientId)) {
            throw new PrereqsAlreadyInstantiatedException(obClientId);
        }
        ObPrereqTemplateVersion version = master.activeVersion()
                .orElseThrow(NoActivePrereqMasterException::new);

        ObClientPrereqs header = new ObClientPrereqs();
        header.setObClientId(obClientId);
        header.setTemplateVersionId(version.getId());
        header.setTemplateVersion(version.getVersion());
        header.setStatus(ObClientPrereqs.Status.IN_PROGRESS);
        ObClientPrereqs saved = headers.save(header);

        Instant boardedAt = Instant.now();
        for (ObPrereqTemplateTask source : master.tasksOf(version.getId())) {
            // An inactive master task is not snapshotted. `isActive` is
            // B-124's way of retiring a task without deleting it from a
            // published version, and boarding a client against something the
            // admin has retired would ask for a document nobody wants.
            if (!source.isActive()) {
                continue;
            }
            ObClientPrereqTask task = new ObClientPrereqTask();
            task.setHeaderId(saved.getId());
            task.setObClientId(obClientId);
            task.setTemplateTaskId(source.getId());
            task.setSequence(source.getSequence());
            // Copied, not referenced. See the class javadoc.
            task.setTitle(source.getTitle());
            task.setDescription(source.getDescription());
            task.setTatDays(source.getTatDays());
            task.setMandatory(source.isMandatory());
            task.setAdHoc(false);
            task.setDueAt(dueAt(boardedAt, source.getTatDays()));
            tasks.save(task);
        }
        return saved;
    }

    /**
     * {@code dueAt} through the working calendar, never a naive addition —
     * CLAUDE.md's rule, and {@code ObJourneyStepLifecycleService#computeDueAt}'s
     * exact idiom. A Friday task with a two-day TAT is not overdue on Sunday.
     */
    Instant dueAt(Instant from, int tatDays) {
        return workingHours.addWorkingHours(from, ObStepTatBudget.hours(workingCalendars, tatDays));
    }

    @Transactional(readOnly = true)
    public java.util.Optional<ObClientPrereqs> headerOf(long obClientId) {
        return headers.findByObClientId(obClientId);
    }

    @Transactional(readOnly = true)
    public List<ObClientPrereqTask> tasksOf(long obClientId) {
        return tasks.findByObClientIdOrderBySequenceAsc(obClientId);
    }

    /**
     * The strip's arithmetic. Derived on read from the task rows, never
     * stored — the same argument {@code ObClientPrereqTask#isOverdue} makes
     * for not storing overdue-ness.
     *
     * @param mandatoryTotal      the progress bar's denominator
     * @param mandatoryVerified   its numerator
     * @param optionalOutstanding non-mandatory tasks neither verified nor
     *                            skipped. <b>These hold the gate too</b>, and
     *                            without the number a screen showing 4/4
     *                            mandatory beside a locked gate looks broken.
     */
    public record Progress(int mandatoryTotal, int mandatoryVerified, int optionalOutstanding) {

        static Progress of(List<ObClientPrereqTask> tasks) {
            int mandatoryTotal = 0;
            int mandatoryVerified = 0;
            int optionalOutstanding = 0;
            for (ObClientPrereqTask task : tasks) {
                if (task.isMandatory()) {
                    mandatoryTotal++;
                    if (task.getStatus() == com.edunext.edutrack.domain.onboarding.ObPrereqTaskStatus.VERIFIED) {
                        mandatoryVerified++;
                    }
                } else if (!task.getStatus().isSettled()) {
                    optionalOutstanding++;
                }
            }
            return new Progress(mandatoryTotal, mandatoryVerified, optionalOutstanding);
        }
    }

    @Transactional(readOnly = true)
    public Progress progressOf(List<ObClientPrereqTask> tasks) {
        return Progress.of(tasks);
    }
}
