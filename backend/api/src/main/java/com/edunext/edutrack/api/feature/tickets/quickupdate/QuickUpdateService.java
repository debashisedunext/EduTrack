package com.edunext.edutrack.api.feature.tickets.quickupdate;

import com.edunext.edutrack.api.feature.tickets.StatusCode;
import com.edunext.edutrack.api.feature.tickets.TicketWire;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.identity.UserRepository;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketAttachment;
import com.edunext.edutrack.domain.tickets.TicketAttachmentRepository;
import com.edunext.edutrack.domain.tickets.TicketEffortLog;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * C-036 · the two-click update, blueprint's S-21.
 *
 * <p>The contract has declared {@code quickUpdateTicket} since D-001 — the
 * frontend has called it since C-018 built the slide-over against D-060's mock
 * — with nothing behind it, the same state C-038 found for {@code
 * reopenTicket} and C-028 for {@code deleteAttachment}: a client that worked
 * against every mock and 404'd the moment it reached a real server.
 *
 * <h2>Six independent fields, six independent send-if-touched checks</h2>
 *
 * <p>Nothing here is one write. {@code status}, {@code pctComplete} and {@code
 * revisedEta} each move a column on {@code tickets} and each write their own
 * {@link TicketHistory} row when they actually change — following {@code
 * PriorityChangeService}'s {@code LEVEL_CHANGED} row, which is the established
 * shape for "one field, one history event" on this ticket. {@code effortHours}
 * appends to {@code ticket_effort_logs} exactly as {@code EffortLogService}'s
 * ordinary path does, auto-stamped from the ticket's own position. All of it
 * happens under one journal lock — {@link TicketJournal#append} takes {@code
 * SELECT … FOR UPDATE} on the parent row before its first write here, and every
 * append after that shares it, on the same {@code MANDATORY} propagation
 * {@code TicketJournal}'s own javadoc argues for.
 *
 * <h2>{@code workNote} has two homes, never both</h2>
 *
 * <p>When effort is logged in the same call, the note rides on that entry's
 * own {@code note} column — matching the wireframe, which draws "Work note"
 * directly under "Log Effort" with the placeholder "What did you do?", and
 * matching the mock. When no effort is logged, there is no effort row for the
 * note to ride on, so it is written as its own {@code WORK_NOTE_ADDED} history
 * entry instead of being silently dropped, which is what the mock does today —
 * a gap worth not reproducing in the real path. Never both: a note attached to
 * an effort entry and also duplicated into history would be the same sentence
 * stored twice for one submit.
 *
 * <h2>{@code attachmentIds} links nothing</h2>
 *
 * <p>Unlike a comment or a handoff, a quick update has nothing of its own for
 * a file to hang off — {@code ticket_attachments.comment_id} is the only
 * linking column the schema carries, and the javadoc on that entity says
 * plainly that a quick-update attachment "hangs off the ticket" like a detail-
 * page one does. {@code useTicketAttachments} already uploaded the file
 * straight to {@code POST /tickets/{ticketId}/attachments} the moment it was
 * picked, before this route ever runs — so by the time {@code attachmentIds}
 * arrives here, the attaching is already done. What is left to do is confirm
 * the claim: every id named must actually belong to this ticket, or {@link
 * AttachmentNotOnTicketException} refuses the whole call, on {@code
 * EffortLogService.requireOwnTicket}'s identical argument for {@code
 * correctsEntryId} — an unchecked foreign id is a cross-ticket read with no
 * scope guard in front of it.
 */
@Service
class QuickUpdateService {

    private static final String STATUS_CHANGED = "STATUS_CHANGED";
    private static final String FIELD_CHANGED = "FIELD_CHANGED";
    private static final String WORK_NOTE_ADDED = "WORK_NOTE_ADDED";

    private final ScopedTickets tickets;
    private final TicketJournal journal;
    private final TicketAttachmentRepository attachments;
    private final UserRepository users;

    QuickUpdateService(ScopedTickets tickets, TicketJournal journal, TicketAttachmentRepository attachments,
                        UserRepository users) {
        this.tickets = tickets;
        this.journal = journal;
        this.attachments = attachments;
        this.users = users;
    }

    /**
     * @throws RevisedEtaReasonRequiredException 400 — {@code revisedEta} sent
     *         with no {@code revisedEtaReason}
     * @throws AttachmentNotOnTicketException 404 — an {@code attachmentIds}
     *         entry belongs to a different ticket, or to none
     */
    @Transactional
    TicketWire.Ticket update(Authentication caller, String ticketCode, QuickUpdateDtos.QuickUpdateRequest request) {

        // Scope first, and only once — everything below works from this row,
        // following every other ticket-mutating service in this package.
        Ticket ticket = tickets.requireByCode(caller, ticketCode);
        Long actor = actorId(caller);

        requireAttachmentsBelongHere(ticket.getId(), request.attachmentIds());

        if (request.status() != null) {
            changeStatus(ticket, request.status(), actor);
        }

        if (request.pctComplete() != null) {
            changePctComplete(ticket, request.pctComplete(), actor);
        }

        String note = blankToNull(request.workNote());
        if (request.effortHours() != null) {
            appendEffort(ticket, requireActorId(caller), request.effortHours(), request.effortDate(), note);
        } else if (note != null) {
            appendWorkNote(ticket, actor, note);
        }

        if (request.revisedEta() != null) {
            reviseEta(ticket, request.revisedEta(), request.revisedEtaReason(), actor);
        }

        return TicketWire.of(ticket, users);
    }

    /** {@code STATUS_CHANGED}, following {@code PriorityChangeService}'s no-op-on-repeat rule. */
    private void changeStatus(Ticket ticket, StatusCode status, Long actor) {
        String previous = ticket.getStatus();
        String next = status.name();
        if (next.equals(previous)) {
            return;
        }
        ticket.setStatus(next);
        journal.append(fieldChanged(ticket, STATUS_CHANGED, "status", previous, next, actor, null));
    }

    /** {@code FIELD_CHANGED}/{@code pctComplete}, following the same no-op rule. */
    private void changePctComplete(Ticket ticket, int pctComplete, Long actor) {
        short previous = ticket.getPctComplete();
        if (pctComplete == previous) {
            return;
        }
        ticket.setPctComplete((short) pctComplete);
        journal.append(fieldChanged(ticket, FIELD_CHANGED, "pctComplete",
                String.valueOf(previous), String.valueOf(pctComplete), actor, null));
    }

    /**
     * Auto-stamped from the ticket's current position — never from the
     * request — exactly as {@code EffortLogService.append} stamps the
     * ordinary path. {@code effortDate} falls back to today, matching the
     * mock's own fallback for a field the contract does not require alongside
     * {@code effortHours}.
     */
    private void appendEffort(Ticket ticket, long actor, BigDecimal hours, LocalDate workDate, String note) {
        TicketEffortLog entry = new TicketEffortLog();
        entry.setTicketId(ticket.getId());
        entry.setCycleNo(ticket.getCurrentCycleNo());
        entry.setStageCode(ticket.getCurrentStage());
        entry.setIterationNo(ticket.getCurrentIteration());
        entry.setUserId(actor);
        entry.setWorkDate(workDate != null ? workDate : LocalDate.now(ZoneOffset.UTC));
        entry.setHours(hours);
        entry.setNote(note);
        journal.append(entry);
    }

    /** See the class javadoc's "two homes, never both" heading. */
    private void appendWorkNote(Ticket ticket, Long actor, String note) {
        TicketHistory entry = new TicketHistory();
        entry.setTicketId(ticket.getId());
        entry.setCycleNo(ticket.getCurrentCycleNo());
        entry.setEventType(WORK_NOTE_ADDED);
        entry.setFieldName("workNote");
        entry.setNewValue(note);
        entry.setActorId(actor);
        entry.setActorType(actor == null ? "SYSTEM" : "USER");
        journal.append(entry);
    }

    /**
     * {@code FIELD_CHANGED}/{@code plannedCloseDate}, with the reason in
     * {@code remarks} — {@code LEVEL_CHANGED}'s shape, not restated as a
     * no-op-on-repeat check: unlike status and pctComplete, a caller resending
     * the same date is still restating a decision and the reason is the
     * record of it, so the row is written whether or not the date actually
     * moves.
     */
    private void reviseEta(Ticket ticket, Instant revisedEta, String reason, Long actor) {
        if (blankToNull(reason) == null) {
            throw new RevisedEtaReasonRequiredException();
        }
        Instant previous = ticket.getPlannedCloseDate();
        Instant next = truncate(revisedEta);
        ticket.setPlannedCloseDate(next);
        journal.append(fieldChanged(ticket, FIELD_CHANGED, "plannedCloseDate",
                previous == null ? null : previous.toString(), next.toString(), actor, reason.trim()));
    }

    private static TicketHistory fieldChanged(Ticket ticket, String eventType, String fieldName,
                                              String oldValue, String newValue, Long actor, String remarks) {
        TicketHistory entry = new TicketHistory();
        entry.setTicketId(ticket.getId());
        entry.setCycleNo(ticket.getCurrentCycleNo());
        entry.setEventType(eventType);
        entry.setFieldName(fieldName);
        entry.setOldValue(oldValue);
        entry.setNewValue(newValue);
        entry.setActorId(actor);
        entry.setActorType(actor == null ? "SYSTEM" : "USER");
        entry.setRemarks(remarks);
        return entry;
    }

    /** See the class javadoc's "attachmentIds links nothing" heading. */
    private void requireAttachmentsBelongHere(Long ticketId, List<Long> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        Set<Long> owned = attachments.findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc(ticketId).stream()
                .map(TicketAttachment::getId)
                .collect(Collectors.toSet());
        for (Long id : attachmentIds) {
            if (!owned.contains(id)) {
                throw new AttachmentNotOnTicketException();
            }
        }
    }

    private static Instant truncate(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Who is changing it, for a history row — {@code actor_id} is nullable
     * there (null means SYSTEM), so this returns null rather than throwing.
     * Built the same defensive way {@code PriorityChangeService.actorId} is,
     * rather than assumed.
     */
    private static Long actorId(Authentication caller) {
        return CallerIdentity.of(caller).map(CallerIdentity::userId).orElse(null);
    }

    /**
     * Who is changing it, for an effort log — {@code ticket_effort_logs.user_id}
     * is {@code NOT NULL}, so unlike {@link #actorId} there is no null to fall
     * back on. Unreachable in practice — the route is behind a capability —
     * following {@code EffortLogService.actorId}'s identical guard: a 500
     * naming the cause is a better outcome than an entry permanently claiming
     * an author it does not have.
     */
    private static long requireActorId(Authentication caller) {
        return CallerIdentity.of(caller)
                .map(CallerIdentity::userId)
                .orElseThrow(() -> new IllegalStateException(
                        "a quick-update effort log reached the service with no identifiable actor; "
                                + "the route's @PreAuthorize should have refused this caller"));
    }
}
