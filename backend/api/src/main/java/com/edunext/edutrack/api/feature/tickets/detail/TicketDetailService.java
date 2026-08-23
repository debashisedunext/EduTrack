package com.edunext.edutrack.api.feature.tickets.detail;

import com.edunext.edutrack.api.feature.tickets.TicketWire;
import com.edunext.edutrack.api.feature.tickets.links.TicketLinkService;
import com.edunext.edutrack.api.feature.transitions.RibbonAssembler;
import com.edunext.edutrack.api.feature.transitions.StageOwnership;
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
 * <p>The current cycle only, on the same reasoning {@link RibbonAssembler}'s
 * own javadoc gives — the caller here already knows which ticket it asked
 * for, and the cycle-selector-aware read is {@code GET
 * /tickets/{ticketId}/ribbon}'s job, not this route's. A ticket with no
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
 * it the first time either one changes without the other. Only
 * {@code handoff}/{@code rework} are decided here: they are exactly what the
 * golden rule answers. {@code close}, {@code reopen}, {@code skip-stage} and
 * the rest of the contract's action vocabulary are a different, still-open
 * question — each is its own role rule, not this one — and are left off
 * rather than guessed at, the same restraint this class already applies to
 * {@code ribbon}.
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
                ribbon.assembleCurrentCycle(ticket, canAdvance),
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
     * even to its own owner. An unidentifiable caller (empty
     * {@link CallerIdentity#of}) gets none, the same deny-by-default that
     * class's own doc requires.
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
        boolean hasLiveStage = ticket.getCurrentStage() != null && !"CLOSED".equals(ticket.getStatus());
        if (identity == null || !hasLiveStage) {
            return List.of();
        }

        List<String> actions = new ArrayList<>();
        if (StageOwnership.mayAdvance(identity, ticket)) {
            actions.add("handoff");
            actions.add("rework");
        }
        if (isSkipCapable(identity) && isCurrentStageSkippable(ticket)) {
            actions.add("skip-stage");
        }
        return List.copyOf(actions);
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
