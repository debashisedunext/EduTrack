package com.edunext.edutrack.api.feature.tickets.list;

import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.domain.masters.Status;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketCycle;
import com.edunext.edutrack.domain.workflow.WorkflowStage;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The filters {@code GET /tickets} accepts, and the keyset predicate that
 * resumes a page.
 *
 * <h2>Every sort column is named here, never by the caller</h2>
 *
 * <p>{@code ?sort=} is matched against {@link #SORTABLE} and anything unmatched
 * falls back to the contract's default. A caller cannot name a column, which is
 * the same rule {@code common.pagination.Cursor} enforces for the cursor
 * itself: a string from the wire reaching {@code ORDER BY} is both an injection
 * surface and a way to sort by a column with no index.
 *
 * <h2>The scope guard is not here</h2>
 *
 * <p>Nothing below narrows by project or assignee for security. {@code
 * ScopedTickets} applies {@code ScopeResolver}'s mandatory specification and
 * ANDs this on top, so a caller sending {@code ?assigneeId=} widens nothing —
 * they filter within what they could already see. Writing any scope rule here
 * would be a second implementation of A-034, in the one place it must not be.
 */
final class TicketListSpecs {

    private TicketListSpecs() {
    }

    /**
     * Wire name → entity attribute. The values are the sortable columns, all of
     * which are indexed or the primary key.
     */
    private static final Map<String, String> SORTABLE = Map.of(
            "createdAt", "createdAt",
            "dateReported", "dateReported",
            "plannedCloseDate", "plannedCloseDate",
            "level", "level",
            "ticketCode", "ticketCode");

    static final String DEFAULT_SORT = "-createdAt";

    /** A leading {@code -} means descending, per the contract's {@code -createdAt} default. */
    record SortKey(String attribute, boolean descending) {

        Sort toSort() {
            Sort.Direction direction = descending ? Sort.Direction.DESC : Sort.Direction.ASC;
            // id breaks ties, and must be in the ORDER BY or the keyset below
            // cannot resume: two rows with the same createdAt would come back in
            // an order the database is free to change between pages.
            return Sort.by(new Sort.Order(direction, attribute), new Sort.Order(direction, "id"));
        }
    }

    static SortKey sortKey(String requested) {
        String raw = (requested == null || requested.isBlank()) ? DEFAULT_SORT : requested.trim();
        boolean descending = raw.startsWith("-");
        String name = descending ? raw.substring(1) : raw;

        String attribute = SORTABLE.get(name);
        if (attribute == null) {
            // Unknown sort is the default, not a 400. A saved view carrying a
            // column that was later renamed should still open.
            return new SortKey("createdAt", true);
        }
        return new SortKey(attribute, descending);
    }

    /**
     * Resume after the last row of the previous page.
     *
     * <p>{@code (sortCol, id) < (k, lastId)} expressed as a disjunction, because
     * MySQL's row-value comparison does not use a composite index the way the
     * expanded form does.
     */
    static Specification<Ticket> after(Cursor cursor, SortKey key) {
        if (cursor == null) {
            return null;
        }
        return (root, query, cb) -> {
            Comparable<?> boundary = parse(cursor.sortKey(), key.attribute());
            if (boundary == null) {
                // A cursor we cannot interpret means the first page, matching
                // Cursor.decode's own contract for input it did not issue.
                return null;
            }
            return keyset(cb, root, key, boundary, cursor.id());
        };
    }

    /**
     * Raw {@code Comparable} on purpose. The sort column is chosen at runtime
     * from {@link #SORTABLE}, so its type is not known statically and the
     * generic overloads of {@code lessThan}/{@code greaterThan} cannot infer it.
     * The cast is safe because {@link #parse} only ever produces the type the
     * named column actually stores.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Predicate keyset(jakarta.persistence.criteria.CriteriaBuilder cb,
                                    jakarta.persistence.criteria.Root<Ticket> root,
                                    SortKey key, Comparable boundary, long lastId) {
        jakarta.persistence.criteria.Path path = root.get(key.attribute());
        Predicate strictlyPast = key.descending()
                ? cb.lessThan(path, boundary)
                : cb.greaterThan(path, boundary);
        Predicate sameValue = cb.equal(path, boundary);
        Predicate tieBreak = key.descending()
                ? cb.lessThan(root.get("id"), lastId)
                : cb.greaterThan(root.get("id"), lastId);
        return cb.or(strictlyPast, cb.and(sameValue, tieBreak));
    }

    /** The stored value for a sort column, from the cursor's text form. */
    private static Comparable<?> parse(String raw, String attribute) {
        try {
            return switch (attribute) {
                case "createdAt", "dateReported", "plannedCloseDate" -> Instant.parse(raw);
                default -> raw;
            };
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** The text form of a row's sort value, for the next cursor. */
    static String sortValueOf(Ticket t, SortKey key) {
        return switch (key.attribute()) {
            case "createdAt" -> String.valueOf(t.getCreatedAt());
            case "dateReported" -> String.valueOf(t.getDateReported());
            case "plannedCloseDate" -> String.valueOf(t.getPlannedCloseDate());
            case "level" -> t.getLevel();
            default -> t.getTicketCode();
        };
    }

    /**
     * C-070 · {@code moduleId} is applied now. It was accepted and ignored until
     * C-065 added the column, which was honest while nothing could answer it and
     * would be a silent lie now — a filter that returns every row is worse than
     * one that returns an error, because nobody checks a grid that looks full.
     */
    static Specification<Ticket> filters(Filters f) {
        return (root, query, cb) -> {
            List<Predicate> and = new ArrayList<>();

            if (notBlank(f.q())) {
                // LIKE rather than the FULLTEXT index A-009 added, because
                // MATCH … AGAINST is not expressible in the Criteria API and a
                // native query here would lose the mandatory scope specification
                // ScopedTickets ANDs on. Correctness before speed; the index is
                // there for when this becomes a native query that keeps scope.
                String like = "%" + f.q().toLowerCase() + "%";
                and.add(cb.or(
                        cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("description")), like),
                        cb.like(cb.lower(root.get("ticketCode")), like)));
            }
            eq(and, cb, root, "projectId", f.projectId());
            eq(and, cb, root, "clientId", f.clientId());
            eq(and, cb, root, "taskTypeId", f.taskTypeId());
            // C-070 · §7.5's module filter — "every open Fees ticket" is the
            // question this list gets asked most once the field exists
            // (blueprint line 986).
            //
            // Narrowed to Integer rather than bound as the Long the contract
            // declares, because `tickets.module_id` is an INT and
            // `Ticket.moduleId` is an Integer. A value that does not fit is not
            // a module any row can carry, so it matches nothing — spelled out
            // as an impossible predicate rather than left to `intValue()`,
            // which would truncate 4294967299 to 3 and quietly return every
            // Fees ticket to somebody who asked for something else.
            if (f.moduleId() != null) {
                long moduleId = f.moduleId();
                and.add(moduleId >= Integer.MIN_VALUE && moduleId <= Integer.MAX_VALUE
                        ? cb.equal(root.get("moduleId"), (int) moduleId)
                        : cb.disjunction());
            }
            eq(and, cb, root, "level", f.level());

            // Dashboard Rework Dev 1, PR 5 · `statusCategory` narrows to a whole
            // work category (TODO is "not started" — NEW or REOPENED; IN_PROGRESS
            // is the whole WIP category), which `status` cannot express since it
            // takes one code. A correlated subquery over `statuses` rather than a
            // hardcoded code list, for the same reason B-039 gave `category` its
            // own column: a category's membership is the master's call, not this
            // query's.
            if (notBlank(f.statusCategory())) {
                Subquery<String> categoryCodes = query.subquery(String.class);
                var status = categoryCodes.from(Status.class);
                categoryCodes.select(status.get("code"))
                        .where(cb.equal(status.get("category"), f.statusCategory()));
                and.add(root.get("status").in(categoryCodes));
            }

            // `status` names exactly one code; `statuses` is several at once, for
            // a figure that counts an explicit set rather than a category — the
            // Blocked card is `ON_HOLD,AWAITING_INFO`. The contract states
            // `statuses` is ignored when `status` is also sent, so `status` wins
            // outright rather than the two being ANDed into an impossible pair.
            if (notBlank(f.status())) {
                and.add(cb.equal(root.get("status"), f.status()));
            } else if (f.statuses() != null && !f.statuses().isEmpty()) {
                and.add(root.get("status").in(f.statuses()));
            }

            eq(and, cb, root, "currentStage", f.stage());
            eq(and, cb, root, "assignedTo", f.assigneeId());
            eq(and, cb, root, "isDelayed", f.isDelayed());
            eq(and, cb, root, "isClientRaised", f.isClientRaised());

            if (Boolean.TRUE.equals(f.reopenedOnly())) {
                and.add(cb.isTrue(root.get("isReopened")));
            }
            if (Boolean.TRUE.equals(f.unassigned())) {
                and.add(cb.isNull(root.get("assignedTo")));
            }
            if (Boolean.TRUE.equals(f.excludeClosed())) {
                and.add(cb.notEqual(root.get("status"), "CLOSED"));
            }

            atOrAfter(and, cb, root, "plannedCloseDate", f.dueFrom());
            before(and, cb, root, "plannedCloseDate", f.dueTo());
            atOrAfter(and, cb, root, "actualCloseDate", f.closedFrom());
            before(and, cb, root, "actualCloseDate", f.closedTo());
            // A-060 · the third date window, and the one the dashboard has been
            // emitting since A-055 with nothing here to receive it. Every card
            // and chart segment that names a period names it over *when the
            // ticket was raised*, which is what `daily_ticket_stats` is keyed
            // by — so without this pair a drill-down opened the right filter
            // over the wrong span: all time, silently.
            atOrAfter(and, cb, root, "dateReported", f.reportedFrom());
            before(and, cb, root, "dateReported", f.reportedTo());

            // Dashboard Rework Dev 1, PR 5 · backs the Today tab's "updated
            // today" and "WIP not updated" figures.
            atOrAfter(and, cb, root, "updatedAt", f.updatedFrom());
            before(and, cb, root, "updatedAt", f.updatedTo());

            // `startedAt`/`finishedAt` read the *current cycle's* per-cycle
            // stamps (ticket_cycles, PR 3), not a ticket-level column — a
            // reopened ticket starts and finishes again in its new cycle, and a
            // ticket-level column would count it once and for ever. Expressed as
            // an EXISTS against the one cycle row that matches
            // (ticketId, currentCycleNo) rather than a join, for the same reason
            // `statusCategory` above is a subquery: nothing else in this
            // specification joins another table, and an EXISTS keeps that true.
            if (f.startedFrom() != null || f.startedTo() != null) {
                and.add(currentCycleWindow(cb, query, root, "startedAt", f.startedFrom(), f.startedTo()));
            }
            if (f.finishedFrom() != null || f.finishedTo() != null) {
                and.add(currentCycleWindow(cb, query, root, "finishedAt", f.finishedFrom(), f.finishedTo()));
            }

            // The Pending Review population, resolved from the stage master
            // rather than a hardcoded VERIFY/SIGNOFF — see
            // `workflow_stages.is_review_stage`'s own migration note. "RESOLVED
            // but not CLOSED" is exactly `status = RESOLVED`: the two are
            // different values of the same column, so RESOLVED already excludes
            // CLOSED without a second predicate.
            if (Boolean.TRUE.equals(f.pendingReview())) {
                Subquery<String> reviewStages = query.subquery(String.class);
                var stage = reviewStages.from(WorkflowStage.class);
                reviewStages.select(stage.get("stageCode")).where(cb.isTrue(stage.get("isReviewStage")));

                and.add(cb.or(
                        cb.equal(root.get("status"), "RESOLVED"),
                        root.get("currentStage").in(reviewStages)));
            }

            return and.isEmpty() ? null : cb.and(and.toArray(Predicate[]::new));
        };
    }

    private static void eq(List<Predicate> and, jakarta.persistence.criteria.CriteriaBuilder cb,
                           jakarta.persistence.criteria.Root<Ticket> root, String attribute, Object value) {
        if (value != null) {
            and.add(cb.equal(root.get(attribute), value));
        }
    }

    private static void atOrAfter(List<Predicate> and, jakarta.persistence.criteria.CriteriaBuilder cb,
                                  jakarta.persistence.criteria.Root<Ticket> root, String attribute, LocalDate day) {
        if (day != null) {
            and.add(cb.greaterThanOrEqualTo(root.get(attribute), day.atStartOfDay().toInstant(ZoneOffset.UTC)));
        }
    }

    /** Inclusive of the whole day, per the contract's "inclusive" on the close-date filters. */
    private static void before(List<Predicate> and, jakarta.persistence.criteria.CriteriaBuilder cb,
                               jakarta.persistence.criteria.Root<Ticket> root, String attribute, LocalDate day) {
        if (day != null) {
            and.add(cb.lessThan(root.get(attribute),
                    day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * {@code EXISTS (SELECT 1 FROM ticket_cycles WHERE ticket_id = ? AND
     * cycle_no = <this ticket's current cycle> AND <attribute> BETWEEN …)}
     * — see {@link #filters}' own note on why an EXISTS and not a join.
     */
    private static Predicate currentCycleWindow(jakarta.persistence.criteria.CriteriaBuilder cb,
                                                jakarta.persistence.criteria.CriteriaQuery<?> query,
                                                jakarta.persistence.criteria.Root<Ticket> root,
                                                String attribute, LocalDate from, LocalDate to) {
        Subquery<Long> sub = query.subquery(Long.class);
        var cycle = sub.from(TicketCycle.class);

        List<Predicate> where = new ArrayList<>();
        where.add(cb.equal(cycle.get("ticketId"), root.get("id")));
        where.add(cb.equal(cycle.get("cycleNo"), root.get("currentCycleNo")));
        if (from != null) {
            where.add(cb.greaterThanOrEqualTo(cycle.get(attribute), from.atStartOfDay().toInstant(ZoneOffset.UTC)));
        }
        if (to != null) {
            where.add(cb.lessThan(cycle.get(attribute), to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
        }
        sub.select(cycle.get("id")).where(where.toArray(Predicate[]::new));
        return cb.exists(sub);
    }

    /** Every filter the contract declares, so the controller signature stays readable. */
    record Filters(
            String q,
            Long projectId,
            Long clientId,
            Integer taskTypeId,
            /** C-070 · applied since the column landed; see {@link #filters}. */
            Long moduleId,
            String level,
            String status,
            String stage,
            Long assigneeId,
            Boolean isDelayed,
            Boolean isClientRaised,
            Boolean reopenedOnly,
            Boolean unassigned,
            Boolean excludeClosed,
            LocalDate dueFrom,
            LocalDate dueTo,
            LocalDate closedFrom,
            LocalDate closedTo,
            LocalDate reportedFrom,
            LocalDate reportedTo,
            /** Dashboard Rework Dev 1, PR 5 · see {@link #filters} for all seven below. */
            String statusCategory,
            List<String> statuses,
            LocalDate updatedFrom,
            LocalDate updatedTo,
            LocalDate startedFrom,
            LocalDate startedTo,
            LocalDate finishedFrom,
            LocalDate finishedTo,
            Boolean pendingReview) {
    }
}
