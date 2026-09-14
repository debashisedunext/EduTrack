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

          Documents are deliberately *not* fetched here. `docsFor` counts clean
          attachments per step and walks the template's document list to decide
          satisfaction, which is two more reads per task, and the stage body
          shows no documents — they stay on the step panel, which asks for one
          task at a time and can afford it.
        */
        Map<Long, List<ObJourneyReadRepository.ItemRow>> items = reads.itemsOfJourney(row.id());
        Map<Long, List<ObJourneyReadRepository.DocRow>> docs = reads.docsOfJourney(row.id());

        List<ObJourneyStepLifecycleDtos.ObJourneyStepDetail> views = rows.stream()
                .map(step -> {
                    ObJourneyReadRepository.StageRef stage = stages.get(step.getId());
                    return ObJourneyStepLifecycleDtos.ObJourneyStepDetail
                            .of(step, rag.ragFor(step),
                                    itemDtos(items.get(step.getId())),
                                    docDtos(docs.get(step.getId())),
                                    backupOwners.effectiveOwnerUserId(step))
                            .withStage(stage == null ? null : stage.stageKey(),
                                    stage == null ? null : stage.stageName())
                            .withTatUsed(tatUsedPercent(step));
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
                parallelGroups(rows));
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
                                : new ObJourneyStepLifecycleDtos.UserRef(i.answeredBy(), null)))
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
