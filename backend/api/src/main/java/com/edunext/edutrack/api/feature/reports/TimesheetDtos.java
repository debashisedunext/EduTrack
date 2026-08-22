package com.edunext.edutrack.api.feature.reports;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * B-063 · the shape {@code GET /users/{userId}/timesheet} answers with.
 *
 * <p>A week is a grid, and the payload says so: {@link Day} is the column
 * header — seven of them, always, Monday first — and {@link Row} is a line
 * through it. The alternative shape, a flat list of entries with the client
 * doing the pivot, was rejected because the two figures the screen exists to
 * show are cross-cutting: a day's total spans rows, and a row's total spans
 * days. Computing them in three clients is three chances to disagree with the
 * ticket's own effort tab.
 */
final class TimesheetDtos {

    private TimesheetDtos() {
    }

    record TimesheetResponse(Timesheet data) {
    }

    /**
     * @param utilisationPct null rather than zero when the week offered no
     *                       working hours at all. Somebody on leave all week has
     *                       no utilisation to report; 0% would read as having
     *                       wasted a week they were never expected to work.
     */
    record Timesheet(UserRef person, String weekStart, String weekEnd,
                     List<Day> days, List<Row> rows,
                     BigDecimal totalHours, BigDecimal capacityHours,
                     BigDecimal utilisationPct, TimesheetApproval approval) {
    }

    /** B-065 · null until an Admin or this resource's own direct manager reviews the week. */
    record TimesheetApprovalRequest(String note) {
    }

    record TimesheetApprovalResponse(TimesheetApproval data) {
    }

    record TimesheetApproval(String weekStart, UserRef approvedBy, Instant approvedAt, String note) {
    }

    /** The contract's {@code UserRef}, local to this feature as every other one is. */
    record UserRef(long id, String displayName, String role, String handle) {
    }

    /**
     * @param isWorkingDay {@code capacityHours > 0}, carried rather than
     *                     inferred: a weekend and a working day somebody is
     *                     wholly on leave for both read zero, and only one of
     *                     them should be greyed out as "not a working day".
     */
    record Day(String date, BigDecimal capacityHours, BigDecimal loggedHours,
               boolean isWorkingDay) {
    }

    /**
     * One ticket × stage × iteration × cycle the person logged against.
     *
     * @param ticketId     the code, {@code CRM-26-00347} — the contract's
     *                     {@code TicketId} is a string, and a numeric id beside
     *                     it would give a ticket two names in one payload.
     * @param stage        the stage the hours were stamped with when they were
     *                     logged, never the ticket's stage now. Null for entries
     *                     predating the ribbon.
     * @param hours        keyed by ISO date, sparse. A dense seven-key map would
     *                     make an eight-row week mostly zeros, and the grid
     *                     already knows which days it is drawing.
     * @param hasCorrection at least one entry here is a compensating row and may
     *                     be negative — marked, never dropped.
     */
    record Row(String ticketId, String ticketTitle, ProjectRef project,
               String stage, String stageName, int iterationNo, int cycleNo,
               Map<String, BigDecimal> hours, BigDecimal totalHours,
               boolean hasCorrection) {
    }

    record ProjectRef(long id, String projectCode, String name) {
    }
}
