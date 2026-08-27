package com.edunext.edutrack.api.feature.tickets.detail;

import com.edunext.edutrack.api.feature.tickets.TicketWire;
import com.edunext.edutrack.api.feature.tickets.links.TicketLinkService;
import com.edunext.edutrack.api.feature.transitions.RibbonAssembler;
import com.edunext.edutrack.api.feature.transitions.StageOwnership;
import com.edunext.edutrack.api.feature.transitions.TerminalStage;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.permission.RolePermissions;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketAttachment;
import com.edunext.edutrack.domain.tickets.TicketAttachmentRepository;
import com.edunext.edutrack.domain.tickets.TicketComment;
import com.edunext.edutrack.domain.tickets.TicketCommentRepository;
import com.edunext.edutrack.domain.tickets.TicketCycle;
import com.edunext.edutrack.domain.tickets.TicketCycleRepository;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.TicketEffortLog;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import com.edunext.edutrack.domain.tickets.TicketWatcherRepository;
import com.edunext.edutrack.domain.clients.ClientRepository;
import com.edunext.edutrack.domain.identity.ProjectRepository;
import com.edunext.edutrack.domain.identity.User;
import com.edunext.edutrack.domain.identity.UserRepository;
import com.edunext.edutrack.domain.workflow.WorkflowStageRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * A-052 · the ticket detail page in one call.
 *
 * <h2>Why one call and not six</h2>
 *
 * <p>S-20 needs ticket, cycles, history, effort, comments, attachments and
 * watchers to render. Fetched separately that is a waterfall: the browser opens
 * one connection, waits a round trip, then fires the rest, and the page's
 * time-to-useful is the <em>sum</em> of those latencies rather than the largest
 * of them. Payload size is not what makes a detail page feel slow — the
 * sequence is.
 *
 * <p>The queries below still run one after another against the database, and
 * that is the point: they share one connection and one read-only transaction,
 * so the cost is six local round trips instead of six network ones. If this
 * ever becomes the bottleneck the answer is to widen the fetch, not to hand the
 * waterfall back to the client.
 *
 * <h2>{@code ribbon} — assembled via {@link RibbonAssembler}</h2>
 *
 * <p><b>The cycle {@code ?cycle=} names</b>, exactly like {@code history} and
 * {@code effort} beside it. It was the current cycle only until this route
 * started passing the parameter through, and the selector was broken by it:
 * stepping back to a sealed cycle 1 rendered cycle 2's stages under cycle 1's
 * history, so the page disagreed with itself. {@link RibbonAssembler#assemble}
 * carries the rest of the rule — a past cycle is sealed, has no live stage and
 * reports its own final iteration. A ticket with no
 * {@code workflow_template_id} — one that predates the workflow designer, or
 * was created before ticket creation resolved a template — gets a
 * {@code Ribbon} with an empty segment list rather than {@code null}, exactly
 * as {@link RibbonAssembler} already answers every lifecycle route; the
 * frontend's empty state ("no workflow ribbon") renders off that emptiness,
 * not off the field's absence.
 *
 * <h2>{@code availableActions} — C-043, the golden rule</h2>
 *
 * <p>Filled in by {@link #availableActions}, which asks
 * {@link StageOwnership#mayAdvance} the same question
 * {@code TransitionService.advance} gates on — <b>one predicate, not two</b>,
 * per that class's own javadoc on why a second copy here would diverge from
 * it the first time either one changes without the other. {@code skip-stage}
 * is C-047's capability rule on top of it.
 *
 * <p><b>{@code close} and {@code reopen} are decided here too, and where they
 * appear is the whole of §4A.1's closing hop.</b> The terminal stage —
 * {@link TerminalStage} — has nothing after it, so {@code handoff} drops off
 * it rather than offering a move {@code POST /handoff} would refuse with
 * {@code NoNextStageException}. What replaces it is {@code close}, and only
 * for the desk that now owns that stage after {@code V20260826_1520}: the PM
 * signs off by handing the ticket to Support, and Support is who decides
 * whether it closes or comes back. Both halves of that decision are offered at
 * once — {@code close} and {@code reopen} together — because a decision with
 * one button is not one. Once it <em>is</em> closed the list is {@code reopen}
 * alone — no stage action survives a terminal status, which is what "no Hand
 * off on a closed ticket" means here. See {@link #mayCloseOrReopen} for which
 * half of that is a capability and which half is a row rule.
 *
 * <h2>{@code reopen} names two different routes, and the status says which</h2>
 *
 * <p>On a <b>CLOSED</b> ticket it is {@code POST /reopen}: a new cycle,
 * {@code reopen_count}, a sealed cycle behind it. On a <b>RESOLVED</b> one
 * standing on the terminal stage it is {@code POST /rework}: a new
 * <em>iteration</em> in the cycle already open, back to the stage
 * {@code can_return_to} names ({@code V20260826_1815}).
 *
 * <p>That is not a shortcut, it is the rule
 * {@code TicketNotClosedException}'s javadoc states outright — "Accepting
 * {@code RESOLVED} here would increment the wrong counter and seal a cycle
 * that had not finished — §4A.2's two counters, confused in the one place it
 * costs most" — and it names {@code RESOLVED} as "the one wrong answer a user
 * would defend". One action code because the desk is answering one question
 * ("does this ticket go back?"); two routes because a cycle and an iteration
 * are not the same thing. {@code TicketDetailHeader} branches on
 * {@code ticket.status} to pick the dialog, and the two can never both apply:
 * a ticket is either closed or it is not.
 *
 * <h2>What {@code ?cycle=} selects, and what it does not</h2>
 *
 * <p>It filters the two append-only journals — history and effort logs —
 * because those <em>are</em> one cycle's record of work, and C-053 renders a
 * past cycle's journey read-only from them.
 *
 * <p>It deliberately does not filter comments, attachments or watchers. Those
 * belong to the ticket rather than to a cycle, and {@code cycle_no} is nullable
 * on both comments and attachments — so a file uploaded outside any cycle would
 * vanish from every view if this filtered on it. A conversation that led to a
 * reopen is also most wanted on the cycle that followed it.
 */
@Service
class TicketDetailService {

    /** {@code tickets.status}, the two values {@link #availableActions} turns on. */
    private static final String RESOLVED = "RESOLVED";
    private static final String CLOSED = "CLOSED";

    private final ScopedTickets tickets;
    private final TicketCycleRepository cycles;

    /**
     * The journals are read through {@link TicketJournal}, never through their
     * repositories. A-037's rule forbids depending on an {@code AppendOnly}
     * repository from outside {@code domain.journal} — for reads as well as
     * writes, because a class holding the repository is one edit away from
     * calling {@code save} on it.
     */
    private final TicketJournal journal;

    private final TicketCommentRepository comments;
    private final TicketAttachmentRepository attachments;
    private final TicketWatcherRepository watchers;
    private final TicketLinkService links;

    /**
     * C-047 · read-only, exactly like {@link #cycles}/{@link #comments} above
     * — this class already only ever reads the stage-sequence side of the
     * ribbon (never journals a transition itself), so a plain repository
     * dependency is consistent with what is already here rather than a new
     * exception to it.
     */
    private final WorkflowStageRepository stages;

    /** Resolves {@code reportedBy}/{@code assignedTo} and watcher ids into the
     * contract's {@code UserRef} — {@code TicketWire}'s own note on why a bare
     * id was wrong here for every one of its eight callers. */
    private final UserRepository users;

    /** Resolves {@code ticket.project}/{@code ticket.client} — {@code TicketWire}'s
     * own note on why this route alone calls its four-argument {@code of}. */
    private final ProjectRepository projects;
    private final ClientRepository clients;

    /** Assembles {@code ribbon} — see the class note above. */
    private final RibbonAssembler ribbon;

    TicketDetailService(ScopedTickets tickets,
                        TicketCycleRepository cycles,
                        TicketJournal journal,
                        TicketCommentRepository comments,
                        TicketAttachmentRepository attachments,
                        TicketWatcherRepository watchers,
                        TicketLinkService links,
                        WorkflowStageRepository stages,
                        UserRepository users,
                        ProjectRepository projects,
                        ClientRepository clients,
                        RibbonAssembler ribbon) {
        this.tickets = tickets;
        this.cycles = cycles;
        this.journal = journal;
        this.comments = comments;
        this.attachments = attachments;
        this.watchers = watchers;
        this.links = links;
        this.stages = stages;
        this.users = users;
        this.projects = projects;
        this.clients = clients;
        this.ribbon = ribbon;
    }

    /**
     * @param requestedCycle {@code null} means the ticket's current cycle
     * @throws com.edunext.edutrack.api.security.scope.TicketNotFoundException
     *         identically for a ticket that does not exist and one this caller
     *         may not see — A-035, indistinguishable on purpose
     */
    @Transactional(readOnly = true)
    TicketDetailDtos.Detail detail(Authentication caller, String ticketCode, Integer requestedCycle) {
        // Scope first, and only once. Everything below reads by ticket id, so a
        // caller who got past this line would see another project's history and
        // no later query would notice.
        Ticket ticket = tickets.requireByCode(caller, ticketCode);
        long ticketId = ticket.getId();

        short cycle = requestedCycle == null
                ? ticket.getCurrentCycleNo()
                : (short) requestedCycle.intValue();

        boolean canAdvance = CallerIdentity.of(caller)
                .map(identity -> StageOwnership.mayAdvance(identity, ticket))
                .orElse(false);

        return new TicketDetailDtos.Detail(
                TicketWire.of(ticket, users, projects, clients),
                cycles.findByTicketIdOrderByCycleNoAsc(ticketId).stream().map(this::toCycle).toList(),
                ribbon.assemble(ticket, cycle, canAdvance),
                journal.historyFor(ticketId, cycle).stream().map(this::toHistory).toList(),
                journal.effortFor(ticketId, cycle).stream().map(this::toEffort).toList(),
                comments.findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc(ticketId).stream()
                        .map(this::toComment).toList(),
                attachments.findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc(ticketId).stream()
                        .map(this::toAttachment).toList(),
                watchers.findByIdTicketId(ticketId).stream()
                        .map(w -> toUserRef(w.getId().getUserId())).filter(java.util.Objects::nonNull).toList(),
                links.viewsFor(caller, ticket),
                availableActions(caller, ticket));
    }

    /**
     * C-043 · what this caller may do to this ticket right now, per the class
     * javadoc above. {@code handoff}/{@code rework} require both halves of
     * {@code canAdvance}: the golden rule ({@link StageOwnership#mayAdvance})
     * <em>and</em> a live stage to advance from — a closed ticket, or one
     * whose current stage code no longer resolves, has nothing to hand off
     * even to its own owner, and neither has one already standing on the
     * template's last stage ({@link TerminalStage}). An unidentifiable caller
     * (empty {@link CallerIdentity#of}) gets none, the same deny-by-default
     * that class's own doc requires.
     *
     * <p>The three branches are ordered by how much of the ribbon still
     * applies, most-finished first: a CLOSED ticket (no stage actions at all,
     * {@code reopen} only), then the terminal stage ({@code close} instead of
     * {@code handoff}), then every stage before it (unchanged since C-043).
     *
     * <p>{@code skip-stage} — C-047 · added on top of the pair above rather
     * than folded into the same {@code mayAdvance} branch, because it is a
     * genuine capability (§2's "Skip a stage" row ticks Admin and PM alone,
     * {@code V20260806_0900}) and not a row rule {@code mayAdvance} narrows —
     * {@code SkipController}'s own javadoc makes the identical point about why
     * its golden-rule check is defensive rather than load-bearing. Checked by
     * role code rather than by re-deriving {@code ticket.skip_stage} from
     * {@code RolePermissions} here, on {@code StageOwnership}'s own reasoning
     * for staying a plain predicate: the two roles that hold the capability
     * are exactly the two the golden rule always admits regardless of
     * assignment, so this is one fact stated once, not a second copy of the
     * permission catalogue. Offered only when the current stage is actually
     * skippable — {@code workflow_stages.is_optional} — so the client never
     * renders a button {@code POST /skip-stage} would 422. A ticket with no
     * template, or standing in a stage not on its own template, is treated as
     * skippable-unchecked exactly as {@code SkipService.requireSkippable}
     * itself does, for the identical reason: there is nothing to validate
     * against, and inventing a refusal here would just hide whichever real
     * problem produced it.
     */
    private List<String> availableActions(Authentication caller, Ticket ticket) {
        CallerIdentity identity = CallerIdentity.of(caller).orElse(null);
        if (identity == null) {
            return List.of();
        }

        List<String> actions = new ArrayList<>();

        // A closed ticket has no live stage, so nothing on the ribbon applies
        // to it — but it is not actionless. Reopening is the one move §3.2
        // leaves from a terminal status, and withholding it here is what used
        // to make a closed ticket look finished for good.
        if (CLOSED.equals(ticket.getStatus())) {
            if (mayCloseOrReopen(identity)) {
                actions.add("reopen");
            }
            return List.copyOf(actions);
        }

        if (ticket.getCurrentStage() == null) {
            return List.of();
        }

        if (TerminalStage.is(stages, ticket, ticket.getCurrentStage())) {
            // The terminal stage — §4A.1's Closed. There is no stage after it,
            // so `handoff` is not withheld here as a permission decision: there
            // is genuinely nowhere to hand the ticket to, and `POST /handoff`
            // would answer NoNextStageException. `close` is what stands in its
            // place, offered only once the sign-off that put the ticket here
            // has made it RESOLVED, because that is the only from_status
            // CloseService will take (workflow_transitions row 12, G-3) — the
            // same "never render a button the route would 422" rule
            // `skip-stage` follows below.
            if (RESOLVED.equals(ticket.getStatus()) && mayCloseOrReopen(identity)) {
                actions.add("close");
                // The desk's other option, and the two are deliberately offered
                // together: a sign-off handed to Support is a decision, and a
                // decision with only one button is not one. `reopen` rather
                // than `rework` because that is the word for what it does to
                // the ticket — see the class javadoc on why the same code means
                // two different routes either side of a close.
                actions.add("reopen");
            }
        } else if (StageOwnership.mayAdvance(identity, ticket)) {
            actions.add("handoff");
            actions.add("rework");
        }

        if (isSkipCapable(identity) && isCurrentStageSkippable(ticket)) {
            actions.add("skip-stage");
        }
        return List.copyOf(actions);
    }

    /**
     * Whether {@code close}/{@code reopen} belong in this caller's list.
     *
     * <p>Two halves, and both are needed.
     *
     * <p><b>The capability.</b> §2's "Close ticket" and "Reopen ticket" rows
     * tick Admin, PM and Support Desk and nobody else, and
     * {@code workflow_transitions} rows 12 and 13 seed exactly those three —
     * G-3 calls both LOCKED. Read by role code rather than re-derived from
     * {@link RolePermissions}, on {@link #isSkipCapable}'s own reasoning for
     * the identical shape.
     *
     * <p><b>The capability is the whole rule.</b> No row narrowing on top of
     * it, and that is a correction: this method briefly also demanded the
     * caller be the ticket's assignee or an Admin, so that a PM handing a
     * ticket to the desk could not then close it themselves. The intent was
     * right and the mechanism was wrong. It made the button disagree with the
     * route — {@code CloseService} and {@code ReopenService} honour all three
     * §2 names regardless of assignment — and "two implementations of the same
     * rule always diverge" is the warning on {@code availableActions}' own
     * contract entry, not advice this class gets to make an exception to. It
     * also broke the case §2 grants these roles <em>for</em>: a ticket whose
     * desk owner has left, or that nobody holds, must stay closable and
     * reopenable by a PM.
     *
     * <p>What actually keeps the PM from closing their own sign-off is
     * ownership, not a hidden button: {@code V20260826_1520} put the terminal
     * stage in Support's hands, so the handoff lands the ticket on the desk.
     * A PM who closes it anyway is exercising a permission §2 gives them on
     * purpose.
     */
    private static boolean mayCloseOrReopen(CallerIdentity identity) {
        return RolePermissions.ADMIN.equals(identity.roleCode())
                || RolePermissions.PM.equals(identity.roleCode())
                || RolePermissions.SUPPORT.equals(identity.roleCode());
    }

    private static boolean isSkipCapable(CallerIdentity identity) {
        return RolePermissions.ADMIN.equals(identity.roleCode()) || RolePermissions.PM.equals(identity.roleCode());
    }

    private boolean isCurrentStageSkippable(Ticket ticket) {
        Long templateId = ticket.getWorkflowTemplateId();
        String stageCode = ticket.getCurrentStage();
        if (templateId == null || stageCode == null) {
            return true;
        }
        return stages.findByTemplateIdAndStageCode(templateId, stageCode)
                .map(stage -> stage.isOptional())
                .orElse(true);
    }

    // ── mapping ──────────────────────────────────────────────────────────────

    /** An id with no matching row resolves to {@code null}, {@code TicketWire}'s
     * own reasoning — a deleted watcher should drop off the list rather than
     * render as a placeholder. */
    private TicketDetailDtos.UserRef toUserRef(Long userId) {
        if (userId == null) {
            return null;
        }
        return users.findById(userId)
                .map(u -> new TicketDetailDtos.UserRef(u.getId(), displayNameOf(u)))
                .orElse(null);
    }

    private static String displayNameOf(User user) {
        String fullName = user.getFullName();
        if (fullName != null && !fullName.isBlank()) {
            return fullName;
        }
        return user.getUsername() != null && !user.getUsername().isBlank() ? user.getUsername() : "Unknown";
    }

    private TicketDetailDtos.Cycle toCycle(TicketCycle c) {
        return new TicketDetailDtos.Cycle(
                c.getCycleNo(), c.isSealed(), c.getStartDate(), c.getActualCloseDate(),
                c.getReopenReason(), c.getEffortHrs());
    }

    /**
     * {@code prevHash} and {@code rowHash} are not surfaced. They are the chain
     * A-042 maintains and A-044 verifies; a detail page has no use for them, and
     * publishing the hashes of an append-only journal hands an attacker the
     * shape of what they would have to forge.
     */
    private TicketDetailDtos.HistoryEntry toHistory(TicketHistory h) {
        return new TicketDetailDtos.HistoryEntry(
                h.getId(), h.getCycleNo(), h.getEventType(), h.getFieldName(), h.getOldValue(),
                h.getNewValue(), h.getActorId(), h.getActorType(), h.getRemarks(),
                h.isCorrection(), h.getCorrectsEntryId(), h.getCreatedAt());
    }

    private TicketDetailDtos.EffortLog toEffort(TicketEffortLog e) {
        return new TicketDetailDtos.EffortLog(
                e.getId(), e.getCycleNo(), e.getStageCode(), e.getIterationNo(), e.getUserId(),
                e.getWorkDate(), e.getHours(), e.getNote(), e.isCorrection(),
                e.getCorrectsEntryId(), e.getLoggedAt());
    }

    /**
     * {@code bodyHtml} is what S-20 renders; {@code bodyText} exists for search
     * and notification previews and would double the payload for nothing here.
     * {@code originalBody} is withheld deliberately — §7.6 keeps an edited
     * comment's first version for moderation, not for the thread to display.
     */
    private TicketDetailDtos.Comment toComment(TicketComment c) {
        return new TicketDetailDtos.Comment(
                c.getId(), c.getAuthorId(), c.getBodyHtml(), c.isInternal(),
                c.getEditedAt() != null, c.getEditedAt(), c.getCreatedAt());
    }

    /**
     * {@code storageKey} is withheld. C-025 hands out time-limited signed URLs;
     * the raw MinIO key is the thing those signatures exist to avoid exposing.
     */
    private TicketDetailDtos.Attachment toAttachment(TicketAttachment a) {
        return new TicketDetailDtos.Attachment(
                a.getId(), a.getFileName(), a.getMimeType(), a.getSizeBytes(), a.getThumbnailKey(),
                a.isClientVisible(), a.getScanStatus(), a.getUploadedBy(), a.getCreatedAt());
    }
}
