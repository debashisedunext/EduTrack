package com.edunext.edutrack.api.feature.onboarding.instances;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import com.edunext.edutrack.domain.onboarding.ObGateStatus;
import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRagService;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepStatus;
import com.edunext.edutrack.domain.onboarding.ObRag;
import com.edunext.edutrack.domain.onboarding.ObStepTatBudget;

/**
 * C-110 · assembles {@code GET /onboarding/journeys/{journeyId}} — the ribbon
 * OB-05 draws when an accordion expands.
 *
 * <p>The arithmetic is {@code ObClientService#journeys}', deliberately: this
 * route and the strip inside {@code getObClient} describe the same journey and
 * a reader comparing the collapsed strip to the expanded ribbon must not find
 * two different percentages. Where the two differ is only in reach — the strip
 * carries {@code ObStepDot}s for a whole client, this carries the full step
 * view for one journey.
 */
@Service
public class ObJourneyReadService {

    private final ObJourneyReadRepository reads;
    private final ObJourneyStepRepository steps;
    private final ObJourneyStepRagService rag;
    private final ObBackupOwnerResolver backupOwners;
    private final WorkingCalendarRepository workingCalendars;

    ObJourneyReadService(ObJourneyReadRepository reads, ObJourneyStepRepository steps,
                         ObJourneyStepRagService rag, ObBackupOwnerResolver backupOwners,
                         WorkingCalendarRepository workingCalendars) {
        this.reads = reads;
        this.steps = steps;
        this.rag = rag;
        this.backupOwners = backupOwners;
        this.workingCalendars = workingCalendars;
    }

    @Transactional(readOnly = true)
    Optional<ObJourneyReadDtos.ObJourneyDetail> find(ObClientScope scope, long journeyId) {
        return reads.find(scope, journeyId).map(this::detail);
    }

    private ObJourneyReadDtos.ObJourneyDetail detail(ObJourneyReadRepository.Row row) {
        List<ObJourneyStep> rows = steps.findByJourneyIdOrderBySequenceAsc(row.id());

        /*
          One query for the whole journey — see `stagesOfJourney`. Resolving the
          stage inside the map below would be a join per task on a screen that
          draws every task at once.

          A step missing from the map is not a state this read produces: the
          query selects every row of the journey and left-joins outward, so a
          task whose template is gone still answers, with key 0. The fallback
          exists so a future caller passing a partial map degrades to "no stage
          resolved" rather than to a NullPointerException on a screen.
        */
        Map<Long, ObJourneyReadRepository.StageRef> stages = reads.stagesOfJourney(row.id());

        /*
          The sub-tasks of every task, in one query — see `itemsOfJourney`. The
          project page's stage body draws each task with its checklist open
          underneath, so fetching them per task would be a request per row on
          first paint.

          Documents likewise, and for the same reason. `docsFor` answers this
          for a single task with two reads — the template's document list and a
          count of the task's clean attachments — so a stage drawing every task
          at once would pay 2N. `docsOfJourney` folds both into one statement;
          satisfaction is still decided in Java, because nothing links a file to
          a checklist entry and the rule is "required entries first, in
          sequence".
        */
        Map<Long, List<ObJourneyReadRepository.ItemRow>> items = reads.itemsOfJourney(row.id());
        Map<Long, List<ObJourneyReadRepository.DocRow>> docs = reads.docsOfJourney(row.id());

        /*
          Who an ownerless task falls to — one lookup for the journey, since
          every task of it belongs to the same project. See
          `implementorOfJourney`, and `inheritedOwner` below for when it applies.
        */
        Long projectImplementor = reads.implementorOfJourney(row.id()).orElse(null);

        List<ObJourneyStepLifecycleDtos.ObJourneyStepDetail> views = rows.stream()
                .map(step -> {
                    ObJourneyReadRepository.StageRef stage = stages.get(step.getId());
                    ObJourneyStepLifecycleDtos.ObJourneyStepDetail view =
                            ObJourneyStepLifecycleDtos.ObJourneyStepDetail
                                    .of(step, rag.ragFor(step),
                                            itemDtos(items.get(step.getId())),
                                            docDtos(docs.get(step.getId())),
                                            backupOwners.effectiveOwnerUserId(step))
                                    .withStage(stage == null ? null : stage.stageKey(),
                                            stage == null ? null : stage.stageName())
                                    .withTatUsed(tatUsedPercent(step));
                    Long inherited = inheritedOwner(step, projectImplementor);
                    return inherited == null ? view : view.withInheritedOwner(inherited);
                })
                .toList();

        ObJourneyStep current = currentStep(rows);
        ObJourneyReadRepository.StageRef currentStage =
                current == null ? null : stages.get(current.getId());

        return new ObJourneyReadDtos.ObJourneyDetail(
                row.id(),
                row.obClientId(),
                row.clientName(),
                new ObJourneyReadDtos.ObProductRef(row.productId(), row.productCode(), row.productName()),
                row.serviceName(),
                ObGateStatus.valueOf(row.gateStatus()),
                row.rag() == null ? null : ObRag.valueOf(row.rag()),
                percentComplete(row.stepsSettled(), row.stepCount()),
                current == null ? null : new ObJourneyReadDtos.ObStepDot(
                        current.getId(), current.getSequence(), current.getName(),
                        current.getStatus().name(), rag.ragFor(current), current.getDependsOnStepId(),
                        currentStage == null ? 0L : currentStage.stageKey(),
                        currentStage == null ? "Ungrouped" : currentStage.stageName()),
                current == null ? null : ObJourneyReadDtos.UserRef.of(
                        current.getOwnerUserId(),
                        current.getOwnerUserId() == null ? null
                                : reads.displayNameOf(current.getOwnerUserId()).orElse(null)),
                row.heldByJourneyId(),
                row.totalTatDays(),
                elapsedTatDays(rows),
                row.startedAt(),
                row.completedAt(),
                row.archivedAt(),
                row.templateId(),
                row.templateVersion(),
                views,
                parallelGroups(rows),
                // Asked once for the whole journey, like the implementor above:
                // every task of a journey belongs to one project, so this is
                // one read rather than one per row.
                reads.implementorManagerOfJourney(row.id()).orElse(null));
    }

    /**
     * The project implementor this task falls to, or {@code null} where the
     * question does not arise.
     *
     * <h2>Last in the chain, not first</h2>
     *
     * <p>Owner, then backup owner, then the project's implementor. A task with
     * a named owner is untouched, and so is one whose owner is absent but whose
     * backup is set — {@link ObBackupOwnerResolver} already answers that case,
     * and overriding it here would hand a leave-covered task to somebody the
     * template never mentioned.
     *
     * <p>So this fires only where both are null: a task the module service
     * pinned nobody to, on a journey created before instantiation learned to
     * apply this default, or one later reassigned to nobody. Those are exactly
     * the rows that would otherwise read as unassigned on a project that does
     * have an implementor.
     *
     * <h2>The server agrees with what this makes the page show</h2>
     *
     * <p>{@code ObJourneyStepLifecycleService#requireOwnership} admits the
     * project's implementor on a step with neither owner nor backup, resolved
     * the same way. Without that, this would paint a task as somebody's and the
     * five transitions would refuse them — a page offering Complete to a caller
     * the server 403s.
     */
    private static Long inheritedOwner(ObJourneyStep step, Long projectImplementor) {
        if (projectImplementor == null) {
            return null;
        }
        if (step.getOwnerUserId() != null || step.getBackupOwnerUserId() != null) {
            return null;
        }
        return projectImplementor;
    }

    /**
     * One task's required documents, with satisfaction resolved.
     *
     * <p>Required entries are marked satisfied first and in sequence, each
     * consuming one clean attachment — {@code docsFor}'s rule verbatim, because
     * nothing links a file to an entry. An optional entry never holds the gate,
     * so it is never reported outstanding: it has nothing to be outstanding
     * against.
     */
    private static List<ObJourneyStepLifecycleDtos.ObJourneyStepDoc> docDtos(
            List<ObJourneyReadRepository.DocRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        long remaining = rows.get(0).cleanCount();
        List<ObJourneyStepLifecycleDtos.ObJourneyStepDoc> out = new ArrayList<>(rows.size());
        for (ObJourneyReadRepository.DocRow doc : rows) {
            boolean satisfied = true;
            if (doc.isRequired()) {
                satisfied = remaining > 0;
                if (satisfied) {
                    remaining--;
                }
            }
            // `attachmentId` stays null: `isSatisfied` is counted rather than
            // matched, so no single file can honestly be named as the one that
            // satisfied a given row.
            out.add(new ObJourneyStepLifecycleDtos.ObJourneyStepDoc(
                    doc.id(), doc.stepId(), doc.label(), doc.isRequired(), satisfied, null));
        }
        return out;
    }

    /**
     * What fraction of one task's TAT budget is gone, as a percentage.
     *
     * <p>Both halves come from the same place the journey-level figure uses —
     * {@link ObJourneyStepRagService#hoursConsumed} over
     * {@link ObStepTatBudget#hours} — so the numerator and the denominator
     * cannot disagree about how long a working day is, and a client wait is
     * excluded from both.
     *
     * <p>Null rather than zero for a task with no budget or nothing consumed:
     * 0% reads as "on time with everything still to do", which is a claim about
     * a task that has not started and whose clock has therefore said nothing.
     */
    private Double tatUsedPercent(ObJourneyStep step) {
        /*
          A task nobody has started reports **null**, not 0%.

          `hoursConsumed` answers zero for it rather than null — its clock has
          simply never run — and printing "TAT used 0%" beside that is a claim
          about elapsed time on a task whose clock has said nothing. The header
          omits the figure instead, which is the honest shape: there is nothing
          to report yet.
        */
        if (step.getStartedAt() == null) {
            return null;
        }
        java.math.BigDecimal consumed = rag.hoursConsumed(step);
        if (consumed == null) {
            return null;
        }
        double budget = ObStepTatBudget.hours(workingCalendars, step.getTatDays()).doubleValue();
        return budget == 0 ? null : (consumed.doubleValue() / budget) * 100d;
    }

    /**
     * One task's sub-tasks, wire-shaped.
     *
     * <p>Null in means a task with no checklist, which is ordinary rather than
     * exceptional — {@code itemsOfJourney} only returns rows for tasks that
     * have some — and it answers an empty list so the field is always present
     * and the caller never has to tell "none" from "not loaded".
     *
     * <p>{@code answeredBy} becomes a bare id with no display name, matching
     * {@code ObJourneyStepLifecycleController.items}: resolving names here
     * would be a users read this route has never carried, for a field the
     * stage body does not print.
     */
    private static List<ObJourneyStepLifecycleDtos.ObJourneyStepItem> itemDtos(
            List<ObJourneyReadRepository.ItemRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .map(i -> new ObJourneyStepLifecycleDtos.ObJourneyStepItem(
                        i.id(), i.stepId(), i.sequence(), i.label(),
                        i.isMandatory(), i.isDone(), i.answer(), i.remark(), i.answeredAt(),
                        i.answeredBy() == null ? null
                                : new ObJourneyStepLifecycleDtos.UserRef(i.answeredBy(), null),
                        i.reviewState(), i.reviewedAt(),
                        i.reviewedBy() == null ? null
                                : new ObJourneyStepLifecycleDtos.UserRef(i.reviewedBy(), null),
                        i.reviewLocked(),
                        i.rowState(), i.submittedAt(),
                        i.submittedBy() == null ? null
                                : new ObJourneyStepLifecycleDtos.UserRef(i.submittedBy(), null),
                        i.outcomeSeenAt(), i.unseenOutcome()))
                .toList();
    }

    /**
     * The service in progress, or null when none is.
     *
     * <p>Null for a journey behind its gate, held behind a sibling, or
     * finished — the contract's own wording, and the reason this is not
     * "the first unfinished step": on a journey nobody has started, the first
     * step is {@code PENDING} and naming it as "in progress" would put a
     * service on the Delayed Projects grid that nobody has begun.
     */
    private static ObJourneyStep currentStep(List<ObJourneyStep> rows) {
        return rows.stream()
                .filter(s -> s.getStatus() == ObJourneyStepStatus.IN_PROGRESS
                        || s.getStatus() == ObJourneyStepStatus.BLOCKED
                        || s.getStatus() == ObJourneyStepStatus.WAITING_ON_CLIENT)
                .findFirst().orElse(null);
    }

    /**
     * Settled over total, rounded down, and 100 for a journey with no steps.
     *
     * <p>{@code ObClientService#percentComplete}'s answer, restated here rather
     * than shared: making one package depend on another's private arithmetic
     * to save four lines is the coupling feature packaging exists to avoid.
     * {@code SKIPPED} counts as settled — a waived service is not outstanding
     * work, and a journey that could never reach 100% because one service was
     * waived would read as permanently stuck.
     */
    private static int percentComplete(int settled, int total) {
        return total == 0 ? 100 : (int) Math.floor(settled * 100.0 / total);
    }

    /**
     * Working days consumed, client waits excluded — {@code ObJourneyStrip}'s
     * {@code utilizedHours} expressed in the unit this schema asks for.
     *
     * <p>Hours divided by the working day rather than counted separately: the
     * two would be a second opinion about the same clock, and
     * {@link ObJourneyStepRagService#hoursConsumed} is the one that already
     * knows a {@code WAITING_ON_CLIENT} step reads its last pause.
     */
    private double elapsedTatDays(List<ObJourneyStep> rows) {
        double hours = rows.stream()
                .map(rag::hoursConsumed)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(java.math.BigDecimal::doubleValue)
                .sum();
        // One working day's hours from the same source a TAT budget is
        // measured in, so the numerator and denominator cannot disagree about
        // how long a day is.
        double hoursPerDay = ObStepTatBudget.hours(workingCalendars, 1).doubleValue();
        return hoursPerDay == 0 ? 0 : hours / hoursPerDay;
    }

    /**
     * Services that may run at once — every step sharing a dependency, grouped.
     *
     * <p>The ribbon draws these as parallel rather than sequential. Steps with
     * no dependency form one group (they can all start the moment the gate
     * opens); the rest group by the step they wait on. A group of one is still
     * a group: dropping singletons would make the array's meaning depend on
     * its length.
     */
    private static List<List<Long>> parallelGroups(List<ObJourneyStep> rows) {
        Map<Long, List<Long>> byDependency = new LinkedHashMap<>();
        for (ObJourneyStep step : rows) {
            byDependency
                    .computeIfAbsent(step.getDependsOnStepId(), key -> new ArrayList<>())
                    .add(step.getId());
        }
        return List.copyOf(byDependency.values());
    }
}
