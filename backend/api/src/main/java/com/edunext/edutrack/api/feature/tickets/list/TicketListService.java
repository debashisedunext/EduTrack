package com.edunext.edutrack.api.feature.tickets.list;

import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageLimit;
import com.edunext.edutrack.domain.tickets.Ticket;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@code GET /tickets} — the row-scoped ticket list.
 *
 * <h2>Scope is applied by {@code ScopedTickets}, and only there</h2>
 *
 * <p>Every query goes through {@link ScopedTickets#page}, which ANDs
 * {@code ScopeResolver}'s mandatory specification underneath whatever the
 * caller asked for. Admin unrestricted; PM and Support to their projects;
 * Developer, QA and Deployment to {@code assignedTo = me}. A caller sending
 * {@code ?projectId=} or {@code ?assigneeId=} narrows within what they can
 * already see and cannot widen it, because their filter is a conjunct rather
 * than a replacement.
 *
 * <h2>Keyset, not offset</h2>
 *
 * <p>CONVENTIONS.md §6, and the reason is this endpoint specifically: offset
 * paging over a table being written to skips and repeats rows, and on a ticket
 * list a skipped row is a ticket nobody works on. The {@code PageRequest} below
 * supplies a LIMIT and never an OFFSET — page 0 always — while
 * {@link TicketListSpecs#after} does the positioning.
 *
 * <p>The boundary arithmetic lives in {@code common.pagination.CursorPage}
 * (A-053) rather than here. Four hand-rolled versions of it already exist in
 * this codebase and had drifted into three different shapes; this is the fifth
 * list and deliberately not the fifth implementation.
 */
@Service
public class TicketListService {

    private final ScopedTickets tickets;
    private final TicketListRefs refs;

    TicketListService(ScopedTickets tickets, TicketListRefs refs) {
        this.tickets = tickets;
        this.refs = refs;
    }

    @Transactional(readOnly = true)
    CursorPage<TicketListDtos.TicketSummary> list(Authentication caller,
                                                  TicketListSpecs.Filters filters,
                                                  String sort,
                                                  String rawCursor,
                                                  Integer rawLimit) {
        int limit = PageLimit.clamp(rawLimit);
        TicketListSpecs.SortKey key = TicketListSpecs.sortKey(sort);
        Cursor cursor = Cursor.decode(rawCursor);

        Specification<Ticket> criteria = Specification
                .allOf(TicketListSpecs.filters(filters), TicketListSpecs.after(cursor, key));

        // Page 0 with size limit+1: a LIMIT with no OFFSET. The cursor predicate
        // above is what moves the window, so this never pages by counting.
        List<Ticket> fetched = tickets
                .page(caller, criteria, PageRequest.of(0, PageLimit.fetchSize(limit), key.toSort()))
                .getContent();

        // Paged over entities, then mapped — the cursor has to name a row by the
        // column it was sorted on, and the summary deliberately does not carry
        // every sortable column.
        CursorPage<Ticket> page = CursorPage.of(fetched, limit,
                t -> new Cursor(TicketListSpecs.sortValueOf(t, key), t.getId()));

        // Resolved once for the page, not once per row: three IN queries for
        // fifty tickets rather than a hundred and fifty point reads. Only the
        // rows actually being returned are resolved — `page.data()` has already
        // dropped the limit+1 probe row, so the extra fetch never costs a lookup.
        TicketListRefs.Resolved resolved = refs.resolve(page.data());

        return new CursorPage<>(
                page.data().stream().map(t -> toSummary(t, resolved)).toList(), page.meta());
    }

    /**
     * Entity to contract shape.
     *
     * <p>Takes the page's resolved references rather than a repository, so this
     * stays a pure mapping and cannot reintroduce a per-row query — the n+1 is
     * the obvious way to write this, and passing the maps in is what makes the
     * cheap version the only one available.
     *
     * <p>The numeric {@code id} is deliberately not carried. The contract names
     * one identifier, {@code ticketId}, and it is the human code; a payload
     * offering both invites consumers to key on the surrogate, which is exactly
     * what the code exists to avoid being.
     */
    public static TicketListDtos.TicketSummary toSummary(Ticket t, TicketListRefs.Resolved refs) {
        return new TicketListDtos.TicketSummary(
                t.getTicketCode(), t.getTitle(),
                refs.project(t.getProjectId()), refs.client(t.getClientId()),
                t.getClientContactId(), t.isClientRaised(), t.getTaskTypeId(),
                // C-070 · the real column since C-065. Widened to Long because
                // that is what the contract declares; the column is an INT
                // because eight seeded modules do not need eight bytes.
                t.getModuleId() == null ? null : t.getModuleId().longValue(),
                t.getLevel(), t.getOriginalLevel(), t.getStatus(), t.getCurrentStage(),
                refs.person(t.getAssignedTo()), refs.person(t.getReportedBy()),
                t.getCurrentCycleNo(), t.getCurrentIteration(), t.getReopenCount(), t.isReopened(),
                t.getDateReported(), t.getPlannedCloseDate(), t.getActualCloseDate(),
                t.isDelayed(), t.getDelayedSince(), t.getEstimatedEffortHrs(), t.getTotalEffortHrs(),
                t.getCommentCount(), t.getAttachmentCount(), t.getCreatedAt());
    }
}
