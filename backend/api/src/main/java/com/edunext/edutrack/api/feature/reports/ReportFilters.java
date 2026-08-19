package com.edunext.edutrack.api.feature.reports;

/**
 * B-060 · the three filters that are not scope, carried to the runner.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>A-063's {@link ReportRunner} took {@code (scope, from, to, projectIds)},
 * which is every parameter the first seven reports needed. The client report
 * cannot be written against it: {@code CLIENT} is the filter §7.8 gives it and
 * there was nowhere for the value to travel.
 *
 * <p>Two of the three were already broken in the same way and it did not show.
 * The contract has declared {@code ?taskTypeId=} and {@code ?level=} on
 * {@code runReport} since A-066, {@link TicketReportRepository} takes both on
 * three of its queries, and every caller passed {@code null} — because
 * {@link ReportController} never accepted them either. So {@code sla-breach}
 * drew a Level control and a Task type control that changed nothing, which is
 * exactly the failure {@link ReportFilterKind}'s own javadoc says a declared
 * filter list exists to prevent: <i>"the user sets it, nothing changes, and
 * the only conclusion available to them is that the screen is broken"</i>.
 * Widening the seam for {@code clientId} and leaving the other two dead beside
 * it would have been the same bug with one fewer excuse.
 *
 * <h2>Why a record and not three parameters</h2>
 *
 * <p>{@code run(scope, from, to, projectIds, clientId, taskTypeId, level)} is
 * seven positional arguments, three of them {@code Long}/{@code String} nulls
 * in most calls. The fourth filter §7.8 adds later is then an eighth argument
 * and a change to all thirteen runners; here it is a field and a change to the
 * one runner that reads it.
 *
 * <h2>What this is deliberately not</h2>
 *
 * <p>Not scope. {@link ReportScope} stays a separate argument, and the split is
 * the point: a filter narrows what the caller asked for and may be ignored, a
 * scope narrows what they are allowed and may not. Folding {@code resourceId}
 * in here would put the one value with a security consequence — the one
 * {@link ReportScope#resourceSubject} overrules for the three delivery roles —
 * into a bag of optional preferences, where the next runner would read it
 * directly and quietly re-open what §2 withholds.
 *
 * @param clientId   whose tickets, or null for every client. Honoured by
 *                   {@code client-report}.
 * @param taskTypeId honoured by {@code effort-summary} and {@code sla-breach}.
 * @param level      LOW/MEDIUM/HIGH/CRITICAL, honoured by {@code sla-breach}.
 *                   A string rather than an enum because it reaches SQL as one
 *                   and the contract already pins the four values; parsing it
 *                   into a Java enum here would add a 400 for a filter every
 *                   other one of which narrows to nothing instead.
 */
record ReportFilters(Long clientId, Long taskTypeId, String level) {

    static final ReportFilters NONE = new ReportFilters(null, null, null);
}
