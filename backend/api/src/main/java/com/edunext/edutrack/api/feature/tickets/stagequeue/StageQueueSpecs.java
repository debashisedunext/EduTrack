package com.edunext.edutrack.api.feature.tickets.stagequeue;

import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.domain.tickets.Ticket;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * C-062 · what the queue selects, and in what order.
 *
 * <h2>Two of these predicates are part of the scope, not of the filter</h2>
 *
 * <p>{@code current_stage = :stage} and {@code status <> 'CLOSED'} look like
 * ordinary filters and are not. The stage queue reads under
 * {@code ScopeResolver.stageQueueScope}, which is wider than §10.2 —
 * project membership rather than {@code assigned_to = me} — and these two
 * narrowings are half of why that widening is safe to make. Without the stage
 * the endpoint degrades into "every ticket on my projects", which is a much
 * larger grant arrived at by leaving a parameter off a URL; without the closed
 * exclusion a queue accumulates an archive nobody is waiting on. Both are
 * therefore applied unconditionally here, and neither is reachable from a query
 * parameter.
 *
 * <h2>Ordered by when the ticket entered the stage, ascending</h2>
 *
 * <p>Which is "time in stage descending" — S-31's rule, the ticket rotting
 * longest on top — expressed as the column the database can actually sort and
 * index. It is not <em>exactly</em> that order: time in stage is measured in
 * working minutes, and two tickets on projects with different holiday calendars
 * can enter a stage in one order and have waited in the other. That is a
 * discrepancy of hours at the boundary between two adjacent rows, and the
 * alternative is sorting in the application — which would order <em>this page</em>
 * of a cursor-paginated list rather than the queue, and put the wrong ticket on
 * top with complete confidence. The screen's own README makes the same argument
 * for not re-sorting client-side.
 */
final class StageQueueSpecs {

    /**
     * Ascending on entry time, id breaking ties.
     *
     * <p>The tie-break is not decoration: two tickets handed off in the same
     * batch share a {@code stage_entered_at} to the microsecond, and without a
     * second ordering column the database may return them in a different order
     * on each page — which for a keyset cursor means a row silently skipped or
     * repeated.
     */
    static final Sort SORT = Sort.by(
            new Sort.Order(Sort.Direction.ASC, "stageEnteredAt"),
            new Sort.Order(Sort.Direction.ASC, "id"));

    private StageQueueSpecs() {
    }

    static Specification<Ticket> filters(String stage, Long projectId, Boolean unassignedOnly) {
        return (root, query, cb) -> {
            List<Predicate> and = new ArrayList<>();

            and.add(cb.equal(root.get("currentStage"), stage));
            and.add(cb.notEqual(root.get("status"), "CLOSED"));

            if (projectId != null) {
                and.add(cb.equal(root.get("projectId"), projectId));
            }
            if (Boolean.TRUE.equals(unassignedOnly)) {
                and.add(cb.isNull(root.get("assignedTo")));
            }
            return cb.and(and.toArray(Predicate[]::new));
        };
    }

    /**
     * Resume after the last row of the previous page.
     *
     * <p>{@code (stageEnteredAt, id) > (k, lastId)} written as a disjunction
     * rather than as a row-value comparison, for the reason
     * {@code TicketListSpecs.after} records: MySQL does not use the composite
     * index for the row-value form.
     */
    static Specification<Ticket> after(Cursor cursor) {
        if (cursor == null) {
            return null;
        }
        Instant boundary;
        try {
            boundary = Instant.parse(cursor.sortKey());
        } catch (RuntimeException malformed) {
            // A cursor we cannot interpret means the first page — Cursor.decode's
            // own contract for input it did not issue.
            return null;
        }
        return (root, query, cb) -> cb.or(
                cb.greaterThan(root.get("stageEnteredAt"), boundary),
                cb.and(cb.equal(root.get("stageEnteredAt"), boundary),
                        cb.greaterThan(root.get("id"), cursor.id())));
    }
}
