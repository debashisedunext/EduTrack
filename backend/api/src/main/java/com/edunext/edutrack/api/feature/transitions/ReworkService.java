package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.api.feature.notifications.events.TicketEventNotifier;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketCycle;
import com.edunext.edutrack.domain.tickets.TicketCycleRepository;
import com.edunext.edutrack.domain.tickets.TicketEffortLog;
import com.edunext.edutrack.domain.workflow.WorkflowStage;
import com.edunext.edutrack.domain.workflow.WorkflowStageRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * C-046 · {@code POST /tickets/{ticketId}/rework} — the shared engine
 * ({@code TransitionService}, C-042) plus the four things a backward move
 * needs that a generic transition does not.
 *
 * <p>Everything the ribbon and the queues need is already free here:
 * {@code advance} increments {@code iterationNo}, leaves {@code cycleNo}
 * alone, bumps {@code rework_count}, seals the open hop and raises
 * {@link TicketStageAdvanced}, which C-045's {@code RibbonLiveBroadcaster}
 * turns into the live ribbon push and the stage-queue nudges. That is the
 * "C-046/047/048 get it for free" C-045's own note promised, and none of it is
 * repeated here.
 *
 * <h2>1 · {@code can_return_to} is checked here, not in {@code advance}</h2>
 *
 * <p>{@code advance} validates that the destination is a stage <em>on the
 * template</em> and deliberately stops there, because {@code ForceMoveService}
 * routes through the same method and its entire purpose is moving where the
 * template does not allow — C-048's contract text says so in as many words.
 * So the return-target rule belongs to the route that has it, which is this
 * one. The mock has enforced it since D-004 and answers 422; this matches,
 * and {@link StageMayNotReturnToException} explains why that is not a 400.
 *
 * <p><b>A ticket with no workflow template is not checked</b>, on
 * {@code TransitionService.resolveToStage}'s own precedent: there is nothing
 * to validate against, and plenty of seeded tickets predate B's designer.
 *
 * <h2>2 · The action is one of the four backward ones</h2>
 *
 * <p>{@code advance} accepts eight action codes and would happily record
 * {@code FORWARD} through this route — a forward move written into the ledger
 * as a rework, with {@code iterationNo} left alone because
 * {@code BACKWARD_ACTIONS} would not match. The ledger is append-only, so that
 * row could never be corrected, only compensated. Narrowed here to the four
 * §4A.1 lists, and defaulted to {@code REWORK} rather than required — the mock
 * has defaulted it since D-004, and QA failing a ticket is the overwhelmingly
 * common case.
 *
 * <h2>3 · The defect list, and why it is folded into the reason</h2>
 *
 * <p>The contract carries {@code defects: string[]}, "expected on a QA
 * failure". <b>There is nowhere to put them.</b>
 * {@code ticket_stage_transitions} has {@code reason} and {@code handoff_note}
 * and no third text column — and adding one is a migration against one of the
 * four append-only, hash-chained tables, which needs Stream A's review and is
 * a great deal of ceremony for a bulleted list.
 *
 * <p>They are therefore appended to the stored {@code reason}, which is
 * {@code TEXT} and where a developer reading the ribbon segment will actually
 * find them. The alternative — accepting the field and dropping it — is the
 * failure this codebase keeps finding elsewhere ({@code
 * PostMessage.attachmentIds}, {@code TicketCreateRequest.watcherIds} before
 * D-062): a field that looks wired from both sides and stores nothing.
 *
 * <h2>4 · Status becomes {@code REWORK}</h2>
 *
 * <p>{@code advance} does not touch {@code status} — correctly, since a
 * handoff, a skip and an override all leave it alone. A backward move is the
 * one transition that means something about the work rather than only about
 * where it is, and {@code REWORK} is a {@code StatusCode} the contract already
 * declares. The mock has set it since D-004.
 *
 * <p>The planned close date deliberately does <b>not</b> move — decision G-2,
 * quoted on the contract's own route description: the original commitment
 * stands, and rework is what {@code iterationNo} measures.
 */
@Service
class ReworkService {

    /**
     * {@code TransitionService.BACKWARD_ACTIONS}, which is package-private to
     * that class and mirrored here rather than opened up — the two are equal
     * by definition (§4A.1's four backward actions) and
     * {@code ReworkServiceTest} asserts they have not drifted, which is the
     * same arrangement {@code TransitionService} itself has with
     * {@code TicketJournal.BACKWARD_ACTIONS}.
     */
    static final Set<String> BACKWARD_ACTIONS =
            Set.of("REWORK", "DEPLOY_FAILED", "VERIFY_FAILED", "SIGNOFF_REJECTED");

    private static final String DEFAULT_ACTION = "REWORK";
    private static final String REWORK_STATUS = "REWORK";

    private final ScopedTickets tickets;
    private final TransitionService transitionService;
    private final WorkflowStageRepository stages;
    private final TicketJournal journal;
    private final TicketCycleRepository cycles;
    private final RibbonAssembler ribbon;
    private final TicketEventNotifier notifier;

    ReworkService(ScopedTickets tickets, TransitionService transitionService, WorkflowStageRepository stages,
                  TicketJournal journal, TicketCycleRepository cycles, RibbonAssembler ribbon,
                  TicketEventNotifier notifier) {
        this.tickets = tickets;
        this.transitionService = transitionService;
        this.stages = stages;
        this.journal = journal;
        this.cycles = cycles;
        this.ribbon = ribbon;
        this.notifier = notifier;
    }

    /**
     * @throws NotABackwardActionException     400 — a real action code that is not a backward move
     * @throws StageMayNotReturnToException    422 — not a {@code can_return_to} target
     * @throws NotCurrentStageOwnerException   422 — the golden rule, from {@code advance}
     * @throws NoOpenStageException            422 — from {@code advance}
     * @throws UnknownTransitionStageException 400 — not a stage on this template, from {@code advance}
     */
    @Transactional
    ReworkDtos.RibbonResponse rework(Authentication caller, String ticketCode, ReworkDtos.ReworkRequest request) {
        Ticket ticket = tickets.requireByCode(caller, ticketCode);

        String actionCode = resolveAction(request.action());
        String toStage = normalize(request.toStageCode());

        // Captured before advance() mutates the ticket — HandoffService's own
        // rule, and for its own reason: the stage, cycle and iteration the
        // confirmed hours belong to are the ones being left, and by the time
        // the effort is written the ticket has already moved.
        String leavingStage = ticket.getCurrentStage();
        short leavingCycle = ticket.getCurrentCycleNo();
        short leavingIteration = ticket.getCurrentIteration();

        requireReturnTargetAllowed(ticket, leavingStage, toStage);

        List<String> defects = cleanDefects(request.defects());

        TransitionDtos.TransitionRequest transitionRequest = new TransitionDtos.TransitionRequest(
                actionCode, toStage, request.toUserId(), null, reasonWithDefects(request.reason(), defects));

        // The golden rule and every other refusal fire in here, before a
        // single row below has been written.
        transitionService.advance(caller, ticket.getId(), transitionRequest);

        ticket.setStatus(REWORK_STATUS);

        if (request.effortHours() != null && request.effortHours().compareTo(BigDecimal.ZERO) > 0) {
            logEffort(caller, ticket, leavingStage, leavingCycle, leavingIteration, request.effortHours());
        }

        // D-037 · §4B.6 row 4, "Sent back for rework → Developer, cc PM".
        // Last, after advance succeeded, on HandoffService's own ordering: a
        // refused rework must never tell a developer about work that did not
        // come back to them. The notifier swallows and logs its own failures.
        notifier.sentBackForRework(ticket, actorOrSystem(caller), ticket.getAssignedTo(), defects.size());

        boolean canAdvance = CallerIdentity.of(caller)
                .map(identity -> StageOwnership.mayAdvance(identity, ticket))
                .orElse(false);
        return new ReworkDtos.RibbonResponse(ribbon.assembleCurrentCycle(ticket, canAdvance));
    }

    /** One of §4A.1's four backward actions, defaulting to {@code REWORK}. */
    private static String resolveAction(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_ACTION;
        }
        String actionCode = normalize(raw);
        if (!BACKWARD_ACTIONS.contains(actionCode)) {
            throw new NotABackwardActionException(actionCode, BACKWARD_ACTIONS);
        }
        return actionCode;
    }

    /**
     * §4A.1's {@code can_return_to}, read off the stage being left.
     *
     * <p>Both codes are compared after normalising, because a template's
     * {@code can_return_to} is authored JSON rather than a foreign key
     * ({@code UnknownTransitionStageException} says as much) and a lower-case
     * entry there would otherwise silently forbid a legal move.
     */
    private void requireReturnTargetAllowed(Ticket ticket, String fromStageCode, String toStageCode) {
        Long templateId = ticket.getWorkflowTemplateId();
        if (templateId == null || fromStageCode == null || fromStageCode.isBlank()) {
            return;
        }
        WorkflowStage from = stages.findByTemplateIdAndStageCode(templateId, fromStageCode).orElse(null);
        if (from == null) {
            // The stage the ticket is standing in is not on its own template.
            // Not this route's to diagnose — advance() will refuse the
            // destination or the move on its own terms, and inventing a
            // refusal here would hide whichever real problem produced it.
            return;
        }
        List<String> allowed = from.getCanReturnTo();
        boolean permitted = allowed != null && allowed.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(ReworkService::normalize)
                .anyMatch(toStageCode::equals);
        if (!permitted) {
            throw new StageMayNotReturnToException(from.getDisplayName(), toStageCode, allowed);
        }
    }

    /**
     * The reason, with the defect list appended when there is one.
     *
     * <p>Rendered as a plain bulleted tail rather than JSON or a delimiter
     * nobody agreed on: this string is read by a developer in the ribbon's
     * history, not parsed. Blank entries are dropped — an empty row in a
     * defect list is a UI artefact, and storing it would print a bullet with
     * nothing after it forever.
     */
    private static String reasonWithDefects(String rawReason, List<String> defects) {
        if (defects.isEmpty()) {
            return rawReason.trim();
        }
        StringBuilder reason = new StringBuilder(rawReason.trim());
        reason.append("\n\nDefects:");
        for (String defect : defects) {
            reason.append("\n• ").append(defect);
        }
        return reason.toString();
    }

    /**
     * The defect list with blanks dropped — an empty row is a UI artefact, and
     * storing it would print a bullet with nothing after it forever, as well as
     * inflating the count in §4B.6's subject line.
     */
    private static List<String> cleanDefects(List<String> raw) {
        return raw == null ? List.of() : raw.stream()
                .filter(defect -> defect != null && !defect.isBlank())
                .map(String::trim)
                .toList();
    }

    /**
     * The confirmation write for the stage being left, stamped with the
     * stage/cycle/iteration captured before {@code advance} moved the ticket
     * on and attributed to whoever is confirming.
     *
     * <p>A near-copy of {@code HandoffService.logEffort}, and deliberately so:
     * that method's own javadoc explains at length why neither route calls
     * {@code EffortLogService} — it stamps from the ticket's <em>current</em>
     * stage, which is exactly wrong once {@code advance} has run — and it is
     * package-private in another feature besides. Two short copies of a write
     * are better than a shared one that has to be told which of two moments it
     * is in.
     */
    private void logEffort(Authentication caller, Ticket ticket, String leavingStage, short leavingCycle,
                           short leavingIteration, BigDecimal hours) {
        long actorId = CallerIdentity.of(caller)
                .map(CallerIdentity::userId)
                .orElseThrow(() -> new IllegalStateException(
                        "a rework reached the effort write with no identifiable actor; "
                                + "the golden rule above should already have refused this caller"));

        TicketEffortLog entry = new TicketEffortLog();
        entry.setTicketId(ticket.getId());
        entry.setCycleNo(leavingCycle);
        entry.setStageCode(leavingStage);
        entry.setIterationNo(leavingIteration);
        entry.setUserId(actorId);
        entry.setWorkDate(java.time.LocalDate.now());
        entry.setHours(hours);
        TicketEffortLog saved = journal.append(entry);

        TicketCycle cycle = cycles.findByTicketIdAndCycleNo(ticket.getId(), saved.getCycleNo())
                .orElseThrow(() -> new IllegalStateException(
                        "rework effort " + saved.getId() + " landed on ticket " + ticket.getId()
                                + " cycle " + saved.getCycleNo() + ", which has no ticket_cycles row"));
        cycle.setEffortHrs(cycle.getEffortHrs().add(saved.getHours()));
        ticket.setTotalEffortHrs(ticket.getTotalEffortHrs().add(saved.getHours()));
    }

    /**
     * Who sent it back. {@code advance} has already refused an unidentifiable
     * caller, so this is never reached with one — tolerated rather than thrown
     * on, {@code HandoffService.actorId}'s own choice, so the notification
     * degrades instead of failing the whole request.
     */
    private static long actorOrSystem(Authentication caller) {
        return CallerIdentity.of(caller).map(CallerIdentity::userId).orElse(0L);
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
