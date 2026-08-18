package com.edunext.edutrack.api.feature.tickets.effort;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageLimit;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketEffortLog;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * C-035 · effort logging, append-only, blueprint §4A.4.
 *
 * <p>Two operations, and the whole task is in the first one: {@link #log} never
 * lets the caller say which stage, iteration or cycle an hour belongs to. Those
 * three are read from the ticket's own row and stamped in — the contract's own
 * words for {@code logEffort} are "never sent by the client, which would let a
 * stale tab attribute effort to the wrong stage", and a browser tab left open
 * across a handoff is exactly the ordinary case that sentence is about.
 *
 * <h2>A correction is a different write from an ordinary entry, and looks like one</h2>
 *
 * <p>{@code EffortLogRequest} carries {@code isCorrection} and
 * {@code correctsEntryId} beside {@code hours} and {@code workDate} because one
 * schema serves both shapes of {@code POST /effort} — but a correction does not
 * use the caller's {@code hours} or {@code workDate} at all. {@link TicketJournal#reverseEffort}
 * computes the exact negation of the entry it reverses and copies its
 * {@code workDate}, and its own javadoc explains why: "the hours are not a
 * parameter, and that is the point" — a caller able to name an arbitrary figure
 * for a reversal could leave a residue in a cell that reads as real work. So
 * {@link #correct} never reads {@code request.hours()} or
 * {@code request.workDate()}; only {@code correctsEntryId} and {@code note} make
 * the trip. This is the same shape of deviation {@code PriorityChangeDtos}
 * documents for {@code reason} — a field the schema states as present and the
 * service does not always consult — recorded here rather than discovered again.
 *
 * <h2>The cross-ticket guard {@code TicketJournal} does not carry</h2>
 *
 * <p>{@link TicketJournal#reverseEffort} takes a bare {@code effortLogId} and
 * trusts it — the reversal it builds copies {@code ticketId} <em>from the
 * original</em>, so nothing there checks that the original belongs to the
 * ticket in this request's URL. Left unchecked, {@code correctsEntryId} would be
 * a way to reverse an entry on any ticket in the system from any other ticket's
 * route, in whichever direction the id happened to resolve — a cross-ticket
 * write {@code ScopedTickets} never sees, because the scope check on the way in
 * is against <em>this</em> ticket, not against the id buried in the body.
 * {@link #requireOwnTicket} is that check, done here because the journal's own
 * guard ({@code requireReversesItsOwnBucket}) only compares the correction
 * <em>it is about to build</em> against the original — which is always true by
 * construction once {@code ticketId} has already been copied from it.
 */
@Service
class EffortLogService {

    private final ScopedTickets tickets;
    private final TicketJournal journal;
    private final EffortLogUserRefs people;

    EffortLogService(ScopedTickets tickets, TicketJournal journal, EffortLogUserRefs people) {
        this.tickets = tickets;
        this.journal = journal;
        this.people = people;
    }

    /**
     * Append an entry, or a correction of one — {@code POST /tickets/{ticketId}/effort}.
     *
     * @throws EffortCorrectionTargetRequiredException 400 — {@code isCorrection}
     *         true with no {@code correctsEntryId}
     * @throws EffortLogNotFoundException 404 — {@code correctsEntryId} names no
     *         entry on this ticket
     */
    @Transactional
    EffortLogDtos.EffortLogDto log(Authentication caller, String ticketCode,
                                   EffortLogDtos.EffortLogRequest request) {

        // Scope first, and only once. Both branches below work from this row.
        Ticket ticket = tickets.requireByCode(caller, ticketCode);

        TicketEffortLog saved = Boolean.TRUE.equals(request.isCorrection())
                ? correct(ticket, request)
                : append(ticket, actorId(caller), request);

        Map<Long, EffortLogDtos.UserRef> resolved = people.resolve(List.of(saved.getUserId()));
        return EffortLogDtos.EffortLogDto.of(saved, resolved);
    }

    /**
     * The ordinary path: stamped from the ticket's current position, appended
     * once.
     */
    private TicketEffortLog append(Ticket ticket, long userId, EffortLogDtos.EffortLogRequest request) {
        TicketEffortLog entry = new TicketEffortLog();
        entry.setTicketId(ticket.getId());
        entry.setCycleNo(ticket.getCurrentCycleNo());
        entry.setStageCode(ticket.getCurrentStage());
        entry.setIterationNo(ticket.getCurrentIteration());
        entry.setUserId(userId);
        entry.setWorkDate(request.workDate());
        entry.setHours(request.hours());
        entry.setNote(blankToNull(request.note()));
        return journal.append(entry);
    }

    /** See the class javadoc — {@code hours} and {@code workDate} are not read here. */
    private TicketEffortLog correct(Ticket ticket, EffortLogDtos.EffortLogRequest request) {
        if (request.correctsEntryId() == null) {
            throw new EffortCorrectionTargetRequiredException();
        }
        requireOwnTicket(ticket.getId(), request.correctsEntryId());
        return journal.reverseEffort(request.correctsEntryId(), blankToNull(request.note()));
    }

    /** See the class javadoc's "cross-ticket guard" heading. */
    private void requireOwnTicket(Long ticketId, Long effortLogId) {
        boolean belongsToThisTicket = journal.effortFor(ticketId, null).stream()
                .anyMatch(entry -> entry.getId().equals(effortLogId));
        if (!belongsToThisTicket) {
            throw new EffortLogNotFoundException();
        }
    }

    /**
     * One cursor page — {@code GET /tickets/{ticketId}/effort-logs}.
     *
     * <p>Filtered and paged in memory over {@link TicketJournal#effortFor}'s
     * full result for this ticket, the same choice {@code TicketDetailService}
     * already made for the same table: a ticket's own effort log is a bounded,
     * per-ticket list — nothing here is the cross-ticket dashboard scan
     * CLAUDE.md's {@code COUNT(*)} rule is aimed at — so paging it server-side in
     * Java costs one read of a small list rather than a second repository
     * surface in a package {@code TicketEffortLogRepository} does not live in
     * (it is {@code domain}'s, per B-005 / A-040 — see {@code CommentRows} for
     * the identical reasoning about why a second read path lives beside the
     * feature instead).
     *
     * @param cycle, stage, iteration null means unfiltered on that axis
     */
    @Transactional(readOnly = true)
    EffortLogDtos.EffortLogListResponse list(Authentication caller, String ticketCode,
                                             Integer cycle, String stage, Integer iteration,
                                             String rawCursor, Integer rawLimit) {

        Ticket ticket = tickets.requireByCode(caller, ticketCode);
        List<TicketEffortLog> all = journal.effortFor(ticket.getId(), null);

        List<TicketEffortLog> filtered = all.stream()
                .filter(e -> cycle == null || e.getCycleNo() == cycle.shortValue())
                .filter(e -> stage == null || stage.equals(e.getStageCode()))
                .filter(e -> iteration == null || e.getIterationNo() == iteration.shortValue())
                .toList();

        Cursor cursor = Cursor.decode(rawCursor);
        List<TicketEffortLog> afterCursor = cursor == null
                ? filtered
                : filtered.stream().filter(e -> e.getId() > cursor.id()).toList();

        int limit = PageLimit.clamp(rawLimit);
        List<TicketEffortLog> fetched = afterCursor.stream().limit(PageLimit.fetchSize(limit)).toList();
        CursorPage<TicketEffortLog> page = CursorPage.of(fetched, limit,
                e -> new Cursor(String.valueOf(e.getId()), e.getId()));

        Map<Long, EffortLogDtos.UserRef> resolved = people.resolve(
                page.data().stream().map(TicketEffortLog::getUserId).toList());
        List<EffortLogDtos.EffortLogDto> data = page.data().stream()
                .map(e -> EffortLogDtos.EffortLogDto.of(e, resolved))
                .toList();

        // Both totals are over `all` — never `filtered` or `fetched` — so a
        // caller browsing one stage's slice, or one page of many, still sees the
        // true current-cycle and grand totals rather than a total scoped to
        // whatever they happened to ask for.
        BigDecimal cycleTotal = sumWhere(all, e -> e.getCycleNo() == ticket.getCurrentCycleNo());
        BigDecimal grandTotal = sumWhere(all, e -> true);

        return new EffortLogDtos.EffortLogListResponse(data,
                new EffortLogDtos.ListMeta(page.meta(), cycleTotal, grandTotal));
    }

    /**
     * Correction rows are included and carry negative hours, so this is already
     * the corrected total — {@link TicketJournal#effortFor}'s own javadoc makes
     * the same point for the same reason.
     */
    private static BigDecimal sumWhere(List<TicketEffortLog> rows, Predicate<TicketEffortLog> matches) {
        BigDecimal total = BigDecimal.ZERO;
        for (TicketEffortLog row : rows) {
            if (matches.test(row)) {
                total = total.add(row.getHours());
            }
        }
        return total;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * {@code user_id} is {@code NOT NULL}, so unlike an attachment's
     * {@code uploaded_by} there is no null to fall back on. Unreachable in
     * practice — the route is behind {@code hasAuthority('ticket.update_progress')} —
     * following {@code CommentService.authorId}'s identical guard and reasoning.
     */
    private static long actorId(Authentication caller) {
        return CallerIdentity.of(caller)
                .map(CallerIdentity::userId)
                .orElseThrow(() -> new IllegalStateException(
                        "an effort log reached the service with no identifiable actor; "
                                + "the route's @PreAuthorize should have refused this caller"));
    }
}
