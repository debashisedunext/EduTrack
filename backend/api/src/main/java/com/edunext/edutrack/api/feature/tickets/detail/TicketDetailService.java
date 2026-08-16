package com.edunext.edutrack.api.feature.tickets.detail;

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
 * <h2>🔴 Two fields the contract declares and this does not return</h2>
 *
 * <p>{@code ribbon} and {@code availableActions} are <b>deliberately null</b>,
 * for the same reason: the rules that produce them are Stream C's and are not
 * written yet.
 *
 * <ul>
 *   <li><b>{@code ribbon}</b> needs stage segments from
 *       {@code ticket_stage_transitions} (C-042, unbuilt) laid against a
 *       workflow template's stage sequence (Stream B's
 *       {@code api/feature/workflow/}, which holds only a README). Neither
 *       exists, so any ribbon returned here would be invented.</li>
 *   <li><b>{@code availableActions}</b> is <b>C-043, the golden rule</b> — only
 *       the current stage owner, plus PM and Admin, may advance a ticket. The
 *       contract's note on this very field says the client renders buttons from
 *       it rather than re-deriving permissions, "because two implementations of
 *       the same rule always diverge". Writing it here before C-043 writes it
 *       there would create exactly the second implementation that warning is
 *       about, and the two would diverge on the one question that matters most:
 *       who may move a ticket.</li>
 * </ul>
 *
 * <p>Both are optional in {@code TicketDetailResponse} — only {@code ticket} is
 * required — so omitting them is a contract-valid answer rather than a broken
 * one. A client sees them absent and renders no ribbon and no action buttons,
 * which is the truthful rendering of a system that cannot yet advance a ticket
 * at all. <b>When C-042 and C-043 land, they are filled in here.</b>
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

    TicketDetailService(ScopedTickets tickets,
                        TicketCycleRepository cycles,
                        TicketJournal journal,
                        TicketCommentRepository comments,
                        TicketAttachmentRepository attachments,
                        TicketWatcherRepository watchers) {
        this.tickets = tickets;
        this.cycles = cycles;
        this.journal = journal;
        this.comments = comments;
        this.attachments = attachments;
        this.watchers = watchers;
    }

    /**
     * @param requestedCycle {@code null} means the ticket's current cycle
     * @throws com.edunext.edutrack.api.security.scope.TicketNotFoundException
     *         identically for a ticket that does not exist and one this caller
     *         may not see — A-035, indistinguishable on purpose
     */
    @Transactional(readOnly = true)
    TicketDetailDtos.Detail detail(Authentication caller, long ticketId, Integer requestedCycle) {
        // Scope first, and only once. Everything below reads by ticket id, so a
        // caller who got past this line would see another project's history and
        // no later query would notice.
        Ticket ticket = tickets.require(caller, ticketId);

        short cycle = requestedCycle == null
                ? ticket.getCurrentCycleNo()
                : (short) requestedCycle.intValue();

        return new TicketDetailDtos.Detail(
                toTicket(ticket),
                cycles.findByTicketIdOrderByCycleNoAsc(ticketId).stream().map(this::toCycle).toList(),
                null,   // ribbon — C-042/C-051, see the class note
                journal.historyFor(ticketId, cycle).stream().map(this::toHistory).toList(),
                journal.effortFor(ticketId, cycle).stream().map(this::toEffort).toList(),
                comments.findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc(ticketId).stream()
                        .map(this::toComment).toList(),
                attachments.findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc(ticketId).stream()
                        .map(this::toAttachment).toList(),
                watchers.findByIdTicketId(ticketId).stream()
                        .map(w -> new TicketDetailDtos.UserRef(w.getId().getUserId())).toList(),
                null);  // availableActions — C-043, see the class note
    }

    // ── mapping ──────────────────────────────────────────────────────────────

    private TicketDetailDtos.Ticket toTicket(Ticket t) {
        return new TicketDetailDtos.Ticket(
                t.getId(), t.getTicketCode(), t.getProjectId(), t.getTitle(), t.getDescription(),
                t.getTaskTypeId(), t.getLevel(), t.getOriginalLevel(), t.getStatus(),
                t.getEnvironment(), t.getDateReported(), t.getReportedBy(), t.getAssignedTo(),
                t.getEstimatedEffortHrs(), t.getTotalEffortHrs(), t.getPlannedCloseDate(),
                t.getActualCloseDate(), t.isReopened(), t.getReopenCount(), t.getCurrentCycleNo(),
                t.isDelayed(), t.getCurrentStage(), t.getCurrentIteration(), t.getReworkCount());
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
