package com.edunext.edutrack.api.feature.tickets.detail;

import com.edunext.edutrack.api.feature.tickets.TicketRefResolver;
import com.edunext.edutrack.api.feature.transitions.RibbonAssembler;
import com.edunext.edutrack.api.feature.tickets.TicketWire;
import com.edunext.edutrack.api.feature.tickets.links.TicketLinkService;
import com.edunext.edutrack.api.feature.transitions.StageOwnership;
import com.edunext.edutrack.api.security.CallerIdentity;
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
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <h2>{@code ribbon} — filled in, and why it was empty for so long</h2>
 *
 * <p>This section used to say the ribbon was <b>deliberately null</b> because
 * the stage-sequence half lived in a Stream B package "which still holds only a
 * README". That stopped being true without this note being revisited:
 * {@code WorkflowStageRepository} exists, every one of the 50,000 seeded tickets
 * carries a {@code workflow_template_id}, and {@code RibbonAssembler} had been
 * assembling this exact shape for {@code HandoffService} and
 * {@code ForceMoveService} for days. The literal {@code null} outlived its own
 * justification, and the screen rendered it as <i>"this ticket has no workflow
 * template, so there is no stage journey to show"</i> — a confident sentence
 * that was false of every ticket in the database.
 *
 * <p>Now assembled by {@link RibbonAssembler}, the same component the two write
 * paths use, so the strip a reader sees and the strip a handoff returns cannot
 * disagree. {@code canAdvance} comes from {@link #canAdvance}, which is also
 * what {@link #availableActions} answers from — one predicate, so the ribbon
 * cannot offer a hop the actions list refuses.
 *
 * <p>⚠ <b>Current cycle only.</b> See the field's own note: {@code ?cycle=}
 * still selects an earlier cycle's history and effort while the ribbon shows
 * the live one. Closing that is C-042's remaining half.
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
     * A-052 · resolves the four reference objects the contract's
     * {@code Ticket} declares. This screen is the one that reads all four,
     * so it is the first route moved off {@code TicketWire.Refs.NONE}.
     */
    private final TicketRefResolver refs;
    /**
     * C-051's strip, which this endpoint declared and never filled — the
     * field sat at a literal {@code null} while {@code HandoffService} and
     * {@code ForceMoveService} had been assembling the same shape for days.
     * The screen read that null as "this ticket has no workflow template",
     * which was false of all 50,000 of them.
     *
     * <p>⚠ <b>Current cycle only.</b> {@code assembleCurrentCycle} is what
     * its name says, so {@code ?cycle=} still selects the history, effort and
     * comments of an earlier cycle while the ribbon keeps showing the live
     * one. That is C-042's remaining half and is not invented here.
     */
    private final RibbonAssembler ribbon;

    TicketDetailService(ScopedTickets tickets,
                        TicketCycleRepository cycles,
                        TicketJournal journal,
                        TicketCommentRepository comments,
                        TicketAttachmentRepository attachments,
                        TicketWatcherRepository watchers,
                        TicketLinkService links,
                        TicketRefResolver refs,
                        RibbonAssembler ribbon) {
        this.tickets = tickets;
        this.cycles = cycles;
        this.journal = journal;
        this.comments = comments;
        this.attachments = attachments;
        this.watchers = watchers;
        this.links = links;
        this.refs = refs;
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
        //
        // ⚠ Resolved by CODE, not by id. The contract's `TicketId` is the
        // ticket code (`^[A-Z][A-Z0-9]{1,9}-\d{2}-\d{5,}$`, e.g.
        // `CRM-26-00347`), so every generated client puts a code in this path
        // segment and a `long` path variable 400s before this method is ever
        // reached. CloseController raised exactly this against `full` and left
        // it for A-052's own task rather than fixing another task's surface;
        // this is that task. Only the boundary changes — everything below
        // still reads by the numeric id, which is what the tables are keyed on.
        Ticket ticket = tickets.requireByCode(caller, ticketCode);
        long ticketId = ticket.getId();

        short cycle = requestedCycle == null
                ? ticket.getCurrentCycleNo()
                : (short) requestedCycle.intValue();

        return new TicketDetailDtos.Detail(
                TicketWire.of(ticket, refs.resolve(ticket)),
                cycles.findByTicketIdOrderByCycleNoAsc(ticketId).stream().map(this::toCycle).toList(),
                ribbon.assembleCurrentCycle(ticket, canAdvance(caller, ticket)),
                journal.historyFor(ticketId, cycle).stream().map(this::toHistory).toList(),
                journal.effortFor(ticketId, cycle).stream().map(this::toEffort).toList(),
                comments.findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc(ticketId).stream()
                        .map(this::toComment).toList(),
                attachments.findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc(ticketId).stream()
                        .map(this::toAttachment).toList(),
                watchers.findByIdTicketId(ticketId).stream()
                        .map(w -> new TicketDetailDtos.UserRef(w.getId().getUserId())).toList(),
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
     */
    private List<String> availableActions(Authentication caller, Ticket ticket) {
        return canAdvance(caller, ticket) ? List.of("handoff", "rework") : List.of();
    }

    /**
     * The golden rule and a live stage to advance from — extracted because the
     * ribbon needs the same answer {@link #availableActions} does, and two
     * copies of it would be two chances for the strip to offer a hop the
     * actions list refuses.
     */
    private boolean canAdvance(Authentication caller, Ticket ticket) {
        CallerIdentity identity = CallerIdentity.of(caller).orElse(null);
        boolean hasLiveStage = ticket.getCurrentStage() != null && !"CLOSED".equals(ticket.getStatus());
        return identity != null && hasLiveStage && StageOwnership.mayAdvance(identity, ticket);
    }

    // ── mapping ──────────────────────────────────────────────────────────────

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
