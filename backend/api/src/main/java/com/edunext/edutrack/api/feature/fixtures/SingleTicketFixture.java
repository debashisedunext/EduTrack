package com.edunext.edutrack.api.feature.fixtures;

import com.edunext.edutrack.api.security.scope.UnscopedAccess;
import com.edunext.edutrack.domain.journal.DirectAppend;
import com.edunext.edutrack.domain.masters.Priority;
import com.edunext.edutrack.domain.masters.TaskType;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketCycle;
import com.edunext.edutrack.domain.tickets.TicketCycleRepository;
import com.edunext.edutrack.domain.tickets.TicketEffortLog;
import com.edunext.edutrack.domain.tickets.TicketEffortLogRepository;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import com.edunext.edutrack.domain.tickets.TicketHistoryRepository;
import com.edunext.edutrack.domain.tickets.TicketRepository;
import com.edunext.edutrack.domain.workflow.TicketStageTransition;
import com.edunext.edutrack.domain.workflow.TicketStageTransitionRepository;
import com.edunext.edutrack.domain.workflow.WorkflowStage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * B-007 · builds one fixture ticket's entire journey — cycles, stage
 * transitions, history, effort logs — inside its own transaction.
 *
 * <p><b>Why this is a separate bean from {@link TicketFixtureGenerator}.</b>
 * Spring's {@code @Transactional} works through the proxy Spring wraps a bean
 * in; a method calling {@code this.otherMethod(...)} from inside the same
 * instance never goes through that proxy, so the annotation is silently
 * ignored. {@link TicketFixtureGenerator#generate} loops 200 times and needs
 * each iteration in its own transaction — one bad ticket must not roll back
 * the other 199 — so the per-ticket work has to live on a different bean,
 * injected and called from outside. {@link TicketCodeAllocator#next} is
 * {@code Propagation.MANDATORY} specifically to fail loudly if this is ever
 * collapsed back into a single self-invoking class.
 *
 * <p>See {@link TicketFixtureGenerator}'s javadoc for what "varied" means
 * across the 200 tickets this is called for, and the hash-chain-columns-are-
 * NULL decision that applies to every append this class makes.
 */
@Component
@Profile("fixtures")
@UnscopedAccess("""
        Seed data has no caller. This runs under the `fixtures` profile only and \
        writes the rows the scope guard later filters, so it is upstream of §10.2 \
        rather than exempt from it — going through ScopedTickets would mean asking \
        which tickets a nonexistent user may see. It is also the one legitimate \
        holder of the three append-only repositories outside A-040: it only ever \
        calls insert().""")
@DirectAppend("""
        Seed data, under the `fixtures` profile only, written single-threaded by a \
        loader that has held this ticket since it created it — so there is no \
        concurrent append to fork the chain and nothing for the per-ticket lock to \
        serialise against. createTicket takes it anyway, for whoever copies this next.

        A-040 flagged this rather than migrating it, because the move is not \
        mechanical: appendHistory passes actor_type = 'USER' with an actor_id that \
        resolveOwner can return null for, and TicketJournal refuses that pair as an \
        unattributable audit row. Worth doing before A-042 lands — from then on a \
        direct insert leaves row_hash NULL, and 200 unchained fixture tickets are 200 \
        findings for A-044's nightly verifier. Ayush's call; B-007 owns this file.""")
class SingleTicketFixture {

    private static final List<String> TITLES = List.of(
            "Checkout fails with 500 on discount codes", "Payment webhook retries indefinitely",
            "Dashboard totals disagree with the ticket list", "Search misses tickets with a hyphen in the title",
            "Session expires while typing a long comment", "Attachment thumbnails not generated for HEIC",
            "Nightly export job silently drops rows", "Ticket ID sequence reset after year change",
            "Client portal login redirects to a blank page", "Bulk reassignment leaves stale cache entries",
            "Invoice PDF renders the wrong tax rate", "Mobile layout overlaps the ribbon on small screens",
            "Two-factor prompt appears twice on refresh", "CSV import rejects valid UTF-8 names",
            "Notification bell count does not clear on read", "Report export times out over 5k rows",
            "Recurring holiday not applied to next year's SLA", "Password reset email lands in spam",
            "Comment mentions do not notify offline users", "Chart colours drift from the design tokens",
            "API rate limit triggers on normal usage", "Duplicate client rows after a re-import",
            "Calendar widget shows the wrong week as current", "Handoff dialog allows an empty effort field",
            "Search index falls behind after a bulk update", "Client SLA badge shows the wrong colour",
            "Timesheet totals double-count a reopened cycle", "Escalation email sent to a deactivated manager",
            "File upload silently truncates over 8 MB", "Project colour tag ignored on the Kanban board",
            "Stale JWT accepted for twelve extra seconds", "Audit log missing the actor on a bulk action",
            "Ticket link breaks after a project rename", "Support plan badge missing on the client card",
            "Working-hours addition off by one on a DST day", "Reopen reason field not required by the UI",
            "Client contact email typo blocks notifications", "Priority chip colour unreadable in dark mode",
            "Effort grid rounds 7.5h down to 7h", "Sign-off stage skips the mandatory reviewer",
            "Deploy pipeline hangs on a stale lock file");

    private static final List<String> REOPEN_REASONS = List.of(
            "Client reports the same issue recurring in production.",
            "Fix did not cover the edge case raised in the original report.",
            "Regression observed after an unrelated release.",
            "Verification was signed off before the client re-tested.");

    private static final List<String> ENVIRONMENTS = List.of("PRODUCTION", "STAGING", "UAT", "DEV");

    private final TicketRepository tickets;
    private final TicketCycleRepository cycles;
    private final TicketStageTransitionRepository transitions;
    private final TicketHistoryRepository history;
    private final TicketEffortLogRepository effortLogs;
    private final TicketCodeAllocator codeAllocator;
    private final WorkingHoursService workingHours;

    SingleTicketFixture(TicketRepository tickets, TicketCycleRepository cycles,
                       TicketStageTransitionRepository transitions, TicketHistoryRepository history,
                       TicketEffortLogRepository effortLogs, TicketCodeAllocator codeAllocator,
                       WorkingHoursService workingHours) {
        this.tickets = tickets;
        this.cycles = cycles;
        this.transitions = transitions;
        this.history = history;
        this.effortLogs = effortLogs;
        this.codeAllocator = codeAllocator;
        this.workingHours = workingHours;
    }

    // ------------------------------------------------------------------
    // One ticket, start to finish
    // ------------------------------------------------------------------

    @Transactional
    void createTicket(FixtureContext ctx, FixtureContext.ProjectRef project, TaskType taskType,
                       Map<String, Priority> priorityByCode, Random random, Instant now,
                       boolean forceBreach, boolean forceRework) {
        Long templateId = ctx.templateIdByTaskType().get(taskType.getCode());
        List<WorkflowStage> stages = ctx.stagesByTemplate().get(templateId);
        Map<String, WorkflowStage> stageByCode = new LinkedHashMap<>();
        for (WorkflowStage s : stages) {
            stageByCode.put(s.getStageCode(), s);
        }

        String level = pickLevel(taskType.getDefaultLevel(), random);
        Instant dateReported = forceBreach
                ? now.minus(45 + random.nextInt(20), ChronoUnit.DAYS)
                : now.minus(random.nextInt(40), ChronoUnit.DAYS).minus(random.nextInt(24), ChronoUnit.HOURS);

        Outcome outcome = forceBreach ? Outcome.EARLY_STAGE : rollOutcome(random);
        int stopIndex = pickStopIndex(stages.size(), outcome, random);

        boolean clientRaised = random.nextInt(100) < 40;
        Long clientId = null;
        Long clientContactId = null;
        if (clientRaised && !ctx.clientsByProject().getOrDefault(project.id(), List.of()).isEmpty()) {
            List<Long> candidates = ctx.clientsByProject().get(project.id());
            clientId = candidates.get(random.nextInt(candidates.size()));
            clientContactId = ctx.primaryContactByClient().get(clientId);
        } else {
            clientRaised = false;
        }

        List<Long> members = ctx.projectMembers().get(project.id());
        Long reportedBy = clientRaised
                ? pickByRole(ctx, members, "SUPPORT").orElseGet(() -> ctx.projectManager().get(project.id()))
                : members.get(random.nextInt(members.size()));

        Ticket ticket = new Ticket();
        ticket.setTicketCode(codeAllocator.next(project.id(), project.code()));
        ticket.setProjectId(project.id());
        ticket.setTitle(TITLES.get(random.nextInt(TITLES.size())));
        ticket.setDescription("Reported via " + (clientRaised ? "client escalation" : "internal report")
                + "; needs investigation and a fix.");
        ticket.setTaskTypeId(taskType.getId());
        ticket.setLevel(level);
        ticket.setOriginalLevel(level);
        ticket.setStatus("NEW");
        ticket.setEnvironment(random.nextInt(100) < 60 ? ENVIRONMENTS.get(random.nextInt(ENVIRONMENTS.size())) : null);
        ticket.setDateReported(dateReported);
        ticket.setReportedBy(reportedBy);
        ticket.setAssignedBy(ctx.projectManager().get(project.id()));
        ticket.setEstimatedEffortHrs(estimateEffort(level, random));
        ticket.setWorkflowTemplateId(templateId);
        ticket.setCurrentStage(stages.get(0).getStageCode());
        ticket.setClientId(clientId);
        ticket.setClientContactId(clientContactId);
        ticket.setClientRaised(clientRaised);
        ticket = tickets.save(ticket);
        Long ticketId = ticket.getId();

        // Per PLAN.md §3.7: hold the per-ticket lock before appending to any of
        // the three hash-chained tables. Nothing else is writing to this row —
        // the loader is single-threaded — but taking the lock is what the real
        // write path does, and this is the pattern future readers should copy.
        tickets.findByIdForUpdate(ticketId);

        TicketCycle cycle1 = new TicketCycle();
        cycle1.setTicketId(ticketId);
        cycle1.setCycleNo((short) 1);
        cycle1.setStartDate(dateReported);
        cycle1.setLevel(level);
        cycle1 = cycles.save(cycle1);

        WalkResult cycle1Result = walkStages(ticketId, (short) 1, stages, stageByCode, project.id(), dateReported,
                stopIndex, forceRework, random, ctx, members);

        boolean closedInCycle1 = stopIndex == stages.size() - 1;
        boolean resolvedPendingClose = stopIndex == stages.size() - 2;

        applyWalkResultToTicket(ticket, cycle1Result, stopIndex, stages, closedInCycle1, resolvedPendingClose,
                random);
        applyWalkResultToCycle(cycle1, cycle1Result, closedInCycle1);

        BigDecimal resolutionHrs = priorityByCode.containsKey(level)
                ? ctx.resolutionHoursByLevel().getOrDefault(level, BigDecimal.valueOf(24))
                : BigDecimal.valueOf(24);
        Instant plannedCloseDate = workingHours.addWorkingHours(dateReported, resolutionHrs, project.id(), null);
        ticket.setPlannedCloseDate(plannedCloseDate);

        if (forceBreach) {
            ticket.setDelayed(true);
            ticket.setDelayedSince(plannedCloseDate);
            if (random.nextBoolean()) {
                String escalatedLevel = escalateOneLevel(level);
                if (!escalatedLevel.equals(level)) {
                    appendHistory(ticketId, (short) 1, "LEVEL_CHANGED", "level", level, escalatedLevel, null,
                            "SYSTEM", "Fixture-seeded breach (B-007) — the real SLA scanner is D-020, "
                                    + "not yet implemented; this row exists so it and the dashboard have a "
                                    + "pre-made breach case to render.");
                    ticket.setLevel(escalatedLevel);
                }
            }
        }

        boolean reopen = closedInCycle1 && random.nextInt(100) < 15;
        if (reopen) {
            reopenIntoCycle2(ticket, cycle1, cycle1Result, stages, stageByCode, project.id(), members, random, ctx);
        }

        ticket.setTotalEffortHrs(sumEffort(ticketId));
        tickets.save(ticket);
    }

    // ------------------------------------------------------------------
    // The stage walk
    // ------------------------------------------------------------------

    /** One planned hop, before any timing is computed. */
    private record HopPlan(short iterationNo, String fromStage, String toStage, String actionCode, String reason) {
    }

    /** What the walk left behind — needed to finish the ticket/cycle rows and to reopen later. */
    private record WalkResult(Instant lastEnteredAt, Long lastToUser, short lastIteration, boolean reworked,
                               Long lastTransitionId) {
    }

    private WalkResult walkStages(Long ticketId, short cycleNo, List<WorkflowStage> stages,
                                   Map<String, WorkflowStage> stageByCode, Long projectId, Instant startAt,
                                   int stopIndex, boolean allowRework, Random random, FixtureContext ctx,
                                   List<Long> members) {
        int reworkAtIndex = allowRework ? firstReworkableIndex(stages, stopIndex) : -1;

        List<HopPlan> plan = new ArrayList<>();
        String fromStage = null;
        short iteration = 1;
        for (int idx = 0; idx <= stopIndex; idx++) {
            String toStage = stages.get(idx).getStageCode();
            plan.add(new HopPlan(iteration, fromStage, toStage, "FORWARD", null));
            fromStage = toStage;

            if (idx == reworkAtIndex) {
                WorkflowStage leaving = stages.get(idx);
                String target = leaving.getCanReturnTo() == null || leaving.getCanReturnTo().isEmpty()
                        ? stages.get(Math.max(0, idx - 1)).getStageCode()
                        : leaving.getCanReturnTo().get(0);
                iteration = (short) (iteration + 1);
                plan.add(new HopPlan(iteration, fromStage, target, reworkActionCode(leaving.getStageCode()),
                        "Returned from " + leaving.getDisplayName() + " for another pass."));
                fromStage = target;

                // Forward again from just after the return target up to stopIndex.
                int resumeFrom = indexOf(stages, target) + 1;
                for (int j = resumeFrom; j <= stopIndex; j++) {
                    String toStage2 = stages.get(j).getStageCode();
                    plan.add(new HopPlan(iteration, fromStage, toStage2, "FORWARD", null));
                    fromStage = toStage2;
                }
                break; // the inner loop already reached stopIndex
            }
        }

        return appendPlannedHops(ticketId, cycleNo, plan, stageByCode, projectId, startAt, random, ctx, members,
                reworkAtIndex >= 0);
    }

    private WalkResult appendPlannedHops(Long ticketId, short cycleNo, List<HopPlan> plan,
                                          Map<String, WorkflowStage> stageByCode, Long projectId, Instant startAt,
                                          Random random, FixtureContext ctx, List<Long> members, boolean reworked) {
        Instant currentTime = startAt;
        Long fromUser = null;
        int seqNo = 1;
        Instant lastEnteredAt = startAt;
        Long lastToUser = null;
        short lastIteration = 1;
        Long lastTransitionId = null;

        for (int i = 0; i < plan.size(); i++) {
            HopPlan hop = plan.get(i);
            WorkflowStage targetDef = stageByCode.get(hop.toStage());
            Long toUser = resolveOwner(ctx, members, targetDef == null ? null : targetDef.getOwnerRole(), random);
            boolean isLastHop = i == plan.size() - 1;

            TicketStageTransition t = new TicketStageTransition();
            t.setTicketId(ticketId);
            t.setCycleNo(cycleNo);
            t.setIterationNo(hop.iterationNo());
            t.setSeqNo(seqNo++);
            t.setFromStage(hop.fromStage());
            t.setToStage(hop.toStage());
            t.setFromUserId(fromUser);
            t.setToUserId(toUser);
            t.setActionCode(hop.actionCode());
            t.setReason(hop.reason());
            t.setEnteredAt(currentTime);
            t = transitions.insert(t);

            appendHistory(ticketId, cycleNo, hop.fromStage() == null ? "CREATED" : "STAGE_ADVANCED", "current_stage",
                    hop.fromStage(), hop.toStage(), toUser, "USER", null);

            if (!isLastHop) {
                BigDecimal hours = stageHours(targetDef, random);
                Instant exitedAt = workingHours.addWorkingHours(currentTime, hours, projectId, toUser);
                int durationMins = hours.multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.HALF_UP).intValue();
                transitions.seal(t.getId(), exitedAt, durationMins);
                logEffort(ticketId, cycleNo, hop.toStage(), hop.iterationNo(), toUser, currentTime, hours, random);
                currentTime = exitedAt;
            } else {
                // The current stage: no seal, no exit — the ticket is here now.
                // Still log some partial effort so the segment is not empty.
                BigDecimal partial = BigDecimal.valueOf(0.5 + random.nextDouble() * 3.5)
                        .setScale(2, RoundingMode.HALF_UP);
                logEffort(ticketId, cycleNo, hop.toStage(), hop.iterationNo(), toUser, currentTime, partial, random);
                lastEnteredAt = currentTime;
                lastToUser = toUser;
                lastIteration = hop.iterationNo();
                lastTransitionId = t.getId();
            }
            fromUser = toUser;
        }
        return new WalkResult(lastEnteredAt, lastToUser, lastIteration, reworked, lastTransitionId);
    }

    private void reopenIntoCycle2(Ticket ticket, TicketCycle cycle1, WalkResult cycle1Result,
                                   List<WorkflowStage> stages, Map<String, WorkflowStage> stageByCode,
                                   Long projectId, List<Long> members, Random random, FixtureContext ctx) {
        Long ticketId = ticket.getId();
        Instant closedAt = cycle1.getActualCloseDate();
        Instant reopenedAt = workingHours.addWorkingHours(closedAt, BigDecimal.valueOf(4 + random.nextInt(40)),
                projectId, null);
        String reason = REOPEN_REASONS.get(random.nextInt(REOPEN_REASONS.size()));

        // Close out cycle 1's CLOSED transition — it was left open (is_current=1)
        // because CLOSED is normally terminal. A reopen is the one case that is
        // not true, so it is sealed here, on the way out, with the time the
        // ticket spent sitting closed as its duration. Without this, the ticket
        // would carry two is_current=1 rows once cycle 2's own current stage is
        // appended, breaking the one-row-per-ticket assumption behind
        // current_ticket_id (A-009).
        BigDecimal closedDwellHours = workingHours.workingHoursBetween(closedAt, reopenedAt, projectId, null);
        int closedDwellMins = closedDwellHours.multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.HALF_UP)
                .intValue();
        transitions.seal(cycle1Result.lastTransitionId(), reopenedAt, closedDwellMins);

        // The ticket is open again: clear the cycle-1 close date so pcd_open
        // (IF(actual_close_date IS NULL, planned_close_date, NULL)) picks it
        // back up for the SLA scan. Re-set below only if cycle 2 also closes.
        ticket.setActualCloseDate(null);

        TicketCycle cycle2 = new TicketCycle();
        cycle2.setTicketId(ticketId);
        cycle2.setCycleNo((short) 2);
        cycle2.setStartDate(reopenedAt);
        cycle2.setLevel(ticket.getLevel());
        cycle2.setReopenReason(reason);
        cycle2 = cycles.save(cycle2);

        appendHistory(ticketId, (short) 2, "REOPENED", "status", "CLOSED", "REOPENED", ticket.getReportedBy(),
                "USER", reason);

        // Resume at the second stage (TRIAGE) rather than INTAKE — a reopen does
        // not repeat intake triage. A single-stage-template edge case (there is
        // none today at 5+ stages) would fall back to stage 0.
        int resumeIndex = Math.min(1, stages.size() - 1);
        int stopIndex2 = resumeIndex + random.nextInt(stages.size() - resumeIndex);
        boolean recloses = random.nextInt(100) < 55;
        int actualStop2 = recloses ? stages.size() - 1 : Math.min(stopIndex2, stages.size() - 2);

        List<HopPlan> plan = new ArrayList<>();
        // The reopen hop itself: from CLOSED (where the ticket was resting) into
        // the resume stage. Not a REWORK loop-back — CLOSED has no
        // can_return_to — so it is modelled as an explicit OVERRIDE, the code
        // the schema reserves for exactly this kind of PM/Admin bypass.
        String resumeStage = stages.get(resumeIndex).getStageCode();
        plan.add(new HopPlan((short) 1, "CLOSED", resumeStage, "OVERRIDE", "Reopen: " + reason));
        String fromStage = resumeStage;
        for (int idx = resumeIndex + 1; idx <= actualStop2; idx++) {
            String toStage = stages.get(idx).getStageCode();
            plan.add(new HopPlan((short) 1, fromStage, toStage, "FORWARD", null));
            fromStage = toStage;
        }

        WalkResult cycle2Result = appendPlannedHops(ticketId, (short) 2, plan, stageByCode, projectId, reopenedAt,
                random, ctx, members, false);

        boolean closedAgain = actualStop2 == stages.size() - 1;
        boolean resolvedAgain = actualStop2 == stages.size() - 2;

        ticket.setReopened(true);
        ticket.setReopenCount((short) (ticket.getReopenCount() + 1));
        ticket.setCurrentCycleNo((short) 2);
        ticket.setCurrentStage(stages.get(actualStop2).getStageCode());
        ticket.setCurrentIteration((short) 1);
        ticket.setStageEnteredAt(cycle2Result.lastEnteredAt());
        ticket.setAssignedTo(cycle2Result.lastToUser());
        ticket.setDelayed(false); // a reopened, actively-worked ticket is not "currently delayed" by this fixture

        if (closedAgain) {
            ticket.setStatus("CLOSED");
            ticket.setActualCloseDate(cycle2Result.lastEnteredAt());
            cycle2.setActualCloseDate(cycle2Result.lastEnteredAt());
            cycle2.setResolutionSummary("Re-verified and closed after the reopen.");
            cycle2.setSealed(true);
        } else if (resolvedAgain) {
            ticket.setStatus("RESOLVED");
        } else if (actualStop2 == resumeIndex) {
            ticket.setStatus("REOPENED");
        } else {
            ticket.setStatus("IN_PROGRESS");
        }
        cycle2.setEffortHrs(sumEffort(ticketId, (short) 2));
        cycles.save(cycle2);
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private void applyWalkResultToTicket(Ticket ticket, WalkResult result, int stopIndex, List<WorkflowStage> stages,
                                          boolean closed, boolean resolvedPendingClose, Random random) {
        ticket.setCurrentStage(stages.get(stopIndex).getStageCode());
        ticket.setCurrentIteration(result.lastIteration());
        ticket.setReworkCount((short) (result.reworked() ? 1 : 0));
        ticket.setStageEnteredAt(result.lastEnteredAt());
        ticket.setAssignedTo(result.lastToUser());
        ticket.setStatus(deriveStatus(stopIndex, closed, resolvedPendingClose, result.reworked(), random));
        if (closed) {
            ticket.setActualCloseDate(result.lastEnteredAt());
        }
    }

    private void applyWalkResultToCycle(TicketCycle cycle, WalkResult result, boolean closed) {
        cycle.setAssignedTo(result.lastToUser());
        cycle.setEffortHrs(sumEffort(cycle.getTicketId(), (short) 1));
        if (closed) {
            cycle.setActualCloseDate(result.lastEnteredAt());
            cycle.setResolutionSummary("Fixed, verified and closed.");
            cycle.setSealed(true);
        }
        cycles.save(cycle);
    }

    private String deriveStatus(int stopIndex, boolean closed, boolean resolvedPendingClose, boolean reworked,
                                 Random random) {
        if (closed) {
            return "CLOSED";
        }
        if (resolvedPendingClose) {
            return "RESOLVED";
        }
        if (stopIndex == 0) {
            return "NEW";
        }
        if (reworked) {
            return "REWORK";
        }
        int roll = random.nextInt(100);
        if (roll < 70) {
            return "IN_PROGRESS";
        }
        return roll < 85 ? "ON_HOLD" : "AWAITING_INFO";
    }

    private enum Outcome {CLOSES_FULLY, RESOLVED_PENDING_CLOSE, EARLY_STAGE}

    private Outcome rollOutcome(Random random) {
        int roll = random.nextInt(100);
        if (roll < 45) {
            return Outcome.CLOSES_FULLY;
        }
        if (roll < 55) {
            return Outcome.RESOLVED_PENDING_CLOSE;
        }
        return Outcome.EARLY_STAGE;
    }

    private int pickStopIndex(int stageCount, Outcome outcome, Random random) {
        return switch (outcome) {
            case CLOSES_FULLY -> stageCount - 1;
            case RESOLVED_PENDING_CLOSE -> Math.max(0, stageCount - 2);
            case EARLY_STAGE -> random.nextInt(Math.max(1, stageCount - 2));
        };
    }

    private int firstReworkableIndex(List<WorkflowStage> stages, int stopIndex) {
        // Strictly before stopIndex — a rework loop needs at least one more
        // forward hop to land on afterwards, or it would BE the resting stage.
        for (int i = 1; i < stopIndex; i++) {
            List<String> returnTo = stages.get(i).getCanReturnTo();
            if (returnTo != null && !returnTo.isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private int indexOf(List<WorkflowStage> stages, String code) {
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).getStageCode().equals(code)) {
                return i;
            }
        }
        return 0;
    }

    private String reworkActionCode(String leavingStageCode) {
        return switch (leavingStageCode) {
            case "QA" -> "REWORK";
            case "DEPLOY" -> "DEPLOY_FAILED";
            case "VERIFY" -> "VERIFY_FAILED";
            case "SIGNOFF" -> "SIGNOFF_REJECTED";
            default -> "REWORK";
        };
    }

    private Long resolveOwner(FixtureContext ctx, List<Long> members, String ownerRole, Random random) {
        if (ownerRole == null) {
            return members.get(random.nextInt(members.size()));
        }
        List<Long> onProject = ctx.usersByRole().getOrDefault(ownerRole, List.of()).stream()
                .filter(members::contains)
                .toList();
        if (!onProject.isEmpty()) {
            return onProject.get(random.nextInt(onProject.size()));
        }
        // PM and Admin can act on any stage in the real system (the golden
        // rule's exception); fall back to the pool for that role org-wide.
        List<Long> anyWithRole = ctx.usersByRole().getOrDefault(ownerRole, List.of());
        return anyWithRole.isEmpty() ? members.get(random.nextInt(members.size()))
                : anyWithRole.get(random.nextInt(anyWithRole.size()));
    }

    private Optional<Long> pickByRole(FixtureContext ctx, List<Long> members, String roleCode) {
        return ctx.usersByRole().getOrDefault(roleCode, List.of()).stream().filter(members::contains).findFirst();
    }

    private BigDecimal stageHours(WorkflowStage stage, Random random) {
        BigDecimal base = stage != null && stage.getSlaHours() != null ? stage.getSlaHours() : BigDecimal.valueOf(6);
        double factor = 0.3 + random.nextDouble() * 1.5; // 0.3x-1.8x the stage's own SLA target
        double hours = base.doubleValue() * factor;
        return BigDecimal.valueOf(Math.max(0.5, hours)).setScale(2, RoundingMode.HALF_UP);
    }

    private void logEffort(Long ticketId, short cycleNo, String stageCode, short iterationNo, Long userId,
                           Instant stageStart, BigDecimal elapsedHours, Random random) {
        if (userId == null) {
            return;
        }
        // Idle vs active: logged effort is a fraction of the elapsed working
        // time, never all of it — a stage sitting open longer than it was
        // worked is the queue-vs-capacity signal §4A.4 exists to show.
        BigDecimal logged = elapsedHours.multiply(BigDecimal.valueOf(0.35 + random.nextDouble() * 0.5))
                .setScale(2, RoundingMode.HALF_UP);
        if (logged.signum() <= 0) {
            logged = BigDecimal.valueOf(0.5);
        }
        TicketEffortLog log = new TicketEffortLog();
        log.setTicketId(ticketId);
        log.setCycleNo(cycleNo);
        log.setStageCode(stageCode);
        log.setIterationNo(iterationNo);
        log.setUserId(userId);
        log.setWorkDate(LocalDate.ofInstant(stageStart, ZoneOffset.UTC));
        log.setHours(logged);
        effortLogs.insert(log);
    }

    private BigDecimal sumEffort(Long ticketId) {
        return effortLogs.findByTicketIdOrderByIdAsc(ticketId).stream()
                .map(TicketEffortLog::getHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal sumEffort(Long ticketId, short cycleNo) {
        return effortLogs.findByTicketIdOrderByIdAsc(ticketId).stream()
                .filter(e -> e.getCycleNo() == cycleNo)
                .map(TicketEffortLog::getHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private void appendHistory(Long ticketId, short cycleNo, String eventType, String fieldName, String oldValue,
                               String newValue, Long actorId, String actorType, String remarks) {
        TicketHistory h = new TicketHistory();
        h.setTicketId(ticketId);
        h.setCycleNo(cycleNo);
        h.setEventType(eventType);
        h.setFieldName(fieldName);
        h.setOldValue(oldValue);
        h.setNewValue(newValue);
        h.setActorId(actorId);
        h.setActorType(actorType);
        h.setRemarks(remarks);
        history.insert(h);
    }

    private String pickLevel(String defaultLevel, Random random) {
        if (random.nextInt(100) >= 20 || defaultLevel == null) {
            return defaultLevel == null ? "MEDIUM" : defaultLevel;
        }
        List<String> ordered = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
        int idx = ordered.indexOf(defaultLevel);
        if (idx < 0) {
            return defaultLevel;
        }
        int shift = random.nextBoolean() ? 1 : -1;
        int shifted = Math.max(0, Math.min(ordered.size() - 1, idx + shift));
        return ordered.get(shifted);
    }

    private String escalateOneLevel(String level) {
        List<String> ordered = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
        int idx = ordered.indexOf(level);
        return idx < 0 || idx == ordered.size() - 1 ? level : ordered.get(idx + 1);
    }

    private BigDecimal estimateEffort(String level, Random random) {
        double[] range = switch (level) {
            case "CRITICAL" -> new double[]{2, 8};
            case "HIGH" -> new double[]{6, 16};
            case "MEDIUM" -> new double[]{12, 32};
            default -> new double[]{20, 60};
        };
        double hours = range[0] + random.nextDouble() * (range[1] - range[0]);
        return BigDecimal.valueOf(hours).setScale(2, RoundingMode.HALF_UP);
    }
}
