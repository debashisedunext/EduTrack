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
class TicketListService {

    private final ScopedTickets tickets;

    TicketListService(ScopedTickets tickets) {
        this.tickets = tickets;
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

        return new CursorPage<>(page.data().stream().map(TicketListService::toSummary).toList(),
                page.meta());
    }

    static TicketListDtos.TicketSummary toSummary(Ticket t) {
        return new TicketListDtos.TicketSummary(
                t.getId(), t.getTicketCode(), t.getTitle(), t.getProjectId(), t.getClientId(),
                t.isClientRaised(), t.getTaskTypeId(), t.getLevel(), t.getOriginalLevel(),
                t.getStatus(), t.getCurrentStage(), t.getAssignedTo(), t.getReportedBy(),
                t.getCurrentCycleNo(), t.getCurrentIteration(), t.getReopenCount(), t.isReopened(),
                t.getDateReported(), t.getPlannedCloseDate(), t.getActualCloseDate(),
                t.isDelayed(), t.getDelayedSince(), t.getEstimatedEffortHrs(), t.getTotalEffortHrs(),
                t.getCommentCount(), t.getAttachmentCount(), t.getCreatedAt());
    }
}
