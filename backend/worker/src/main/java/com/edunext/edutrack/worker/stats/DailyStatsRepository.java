package com.edunext.edutrack.worker.stats;

import com.edunext.edutrack.domain.masters.WorkingHoursService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A-051 · recomputes one day of summary rows from the source tables.
 *
 * <h2>⚠️ Stream D's directory — needs Debashis's sign-off</h2>
 *
 * <p>TEAM-PLAN.md §6 reads {@code worker/ → D, all schedulers (A's hash
 * verifier is the exception)}, and this class is neither the hash verifier nor
 * Stream D's. It was built by A-051 and extended by A-056 twice — once for
 * {@code type_counts}, once for {@code assigned_in_progress} below — then by
 * A-057 for the SLA columns, by A-062 for the resource-keyed aging and due
 * counts, and now by A-059 for {@code client_daily_stats}, so the precedent is
 * established in the code and nowhere in the ownership map.
 * <b>Flagged rather than edited quietly</b>, per CLAUDE.md. The ownership row
 * wants amending to carve out {@code worker/stats/} for Stream A the way the
 * hash verifier already is, or these edits keep arriving unannounced.
 *
 * <p>A-059 is the first of these to add a <em>table</em> rather than columns,
 * which is DEPENDENCIES.md #20's stated deadline arriving: that row says the
 * decision must land "before A-058", and A-059 has reached it first. The scope
 * of what is being assumed has therefore grown — five edits ago this was two
 * SUMs on an existing statement, and it is now a third summary table with its
 * own refresh method on the scheduler's critical path.
 *
 * <p><b>A-058 is the sixth edit and the deadline itself, now passed.</b> It
 * adds a fourth summary table, two columns, three refresh methods, and — new in
 * kind — a <em>constructor dependency</em>, {@link WorkingHoursService}, so
 * this class is no longer purely a set of statements over the source tables.
 * Nothing has been done differently on that account, because doing it quietly
 * is the failure mode CLAUDE.md names; it is recorded here and in the pull
 * request, where {@code .github/CODEOWNERS} still auto-requests Debashis.
 *
 * <h2>Recompute, never accumulate</h2>
 *
 * <p>Every figure below is derived from scratch for the given date, so running
 * a day twice produces the same rows and running it after an outage produces
 * the right ones. An accumulating counter would be cheaper and would drift with
 * no way back — the first missed increment is permanent, and nothing in the
 * output says so. That is why {@code A-050} declared {@code computed_at}: a
 * stale row is then visible rather than merely wrong.
 *
 * <h2>Set-based, one statement per day</h2>
 *
 * <p>All projects in one {@code GROUP BY} rather than a query per project. The
 * per-project loop is the shape that looks harmless at three projects and is
 * the reason the dashboard needed summary tables in the first place.
 *
 * <h2>🔴 What is faithfully historical, and what is not</h2>
 *
 * <p>This distinction is the whole correctness story of the table, and it is
 * invisible in the output:
 *
 * <ul>
 *   <li><b>Faithful.</b> Created, closed and reopened counts, everything
 *       "open at end of day", the aging buckets, and effort hours — all of
 *       these derive from timestamps the rows carry ({@code date_reported},
 *       {@code actual_close_date}, {@code ticket_cycles.start_date},
 *       {@code ticket_effort_logs.work_date}). Recomputing 3 August next year
 *       gives what was true on 3 August.</li>
 *   <li><b>Not faithful — {@code assigned_to}.</b> There is no assignment
 *       history table, so a resource's past days are computed against
 *       <em>today's</em> assignee. Reassign a ticket and last week's
 *       resource rows change the next time they are recomputed. Effort is
 *       unaffected, because {@code ticket_effort_logs} records who did the
 *       work and when.
 *       <p>Reconstructing it is possible — {@code ticket_history} carries
 *       {@code ASSIGNED} events with timestamps — but that is a join over the
 *       audit log per day per resource, and it belongs with A-069's resource
 *       profile rather than being smuggled in here. <b>Recorded so that
 *       whoever notices a resource chart changing shape retrospectively finds
 *       the reason written down.</b></li>
 *   <li><b>Not faithful — {@code planned_close_date}.</b> The same shape of
 *       problem and it has been here since A-051, unnamed until A-062 made it
 *       visible. Due dates are mutable and carry no history, so the delayed
 *       columns — and now {@code assigned_due_today} /
 *       {@code assigned_due_next_7} — recompute a past day against
 *       <em>today's</em> commitment. Pushing a deadline out therefore repairs
 *       last week's delayed count as well as this week's. It is worth stating
 *       because the due columns make it reachable: "how much was due last
 *       Tuesday" answers with what is due now, and only the columns derived
 *       from immutable timestamps are safe to read as history.</li>
 * </ul>
 *
 * <h2>"Delayed" is derived, not read from the flag</h2>
 *
 * <p>{@code tickets.is_delayed} is a current-state flag and says nothing about
 * last Tuesday. Delay is therefore computed as "still open at end of day, and
 * {@code planned_close_date} already past" — a definition that is reproducible
 * for any date. It can differ from the flag for today, and where they differ
 * the derivation is the one that can be checked.
 */
@Repository
class DailyStatsRepository {

    private final JdbcClient jdbc;

    /**
     * A-058 · the only figure on this class that SQL cannot compute, and the
     * reason is CLAUDE.md rather than convenience.
     *
     * <p>Widget 19's handoff latency is the gap between one stage being left
     * and the next being entered, and "All SLA and duration maths use the
     * working calendar" applies to it exactly. {@code TIMESTAMPDIFF} would
     * report a Friday-evening handoff picked up on Monday morning as
     * 2,880 minutes of queue waste — a false statement about a team, on the
     * one chart whose entire purpose is finding queue waste.
     *
     * <p>Every other duration here is already calendar-corrected before it
     * arrives: {@code ticket_stage_transitions.duration_mins} is written in
     * working minutes by Stream C's transition service, and effort is logged in
     * hours by a person. The gap between two transitions is the one interval
     * nothing has computed yet, so this class computes it — see
     * {@link #refreshStageStats}.
     */
    private final WorkingHoursService workingHours;

    DailyStatsRepository(JdbcClient jdbc, WorkingHoursService workingHours) {
        this.jdbc = jdbc;
        this.workingHours = workingHours;
    }

    /** The first day there is anything to summarise, or empty on a virgin database. */
    Optional<LocalDate> earliestActivity() {
        return jdbc.sql("SELECT MIN(DATE(date_reported)) FROM tickets")
                .query(LocalDate.class)
                .optional();
    }

    /**
     * Where the next backfill pass starts: the day after the newest summarised
     * day below {@code before}, or {@code earliest} when nothing below it has
     * been summarised yet. Empty once history reaches {@code before}.
     *
     * <p><b>Calendar days, not days on which a ticket was reported.</b> The
     * shorter version of this asks which <em>reported</em> dates lack a row,
     * and it leaves holes that never close. A day with no new ticket still has
     * open tickets, an aging profile and a delayed count, and the trend widgets
     * read every one of them. Given tickets reported in April and August and
     * nothing between, the reported-dates version fills April, jumps to August,
     * and then — because it only ever asks about dates that appear in
     * {@code tickets} — reports itself complete with May, June and July
     * missing for ever. Advancing one calendar day at a time cannot skip.
     *
     * <p>The cost is worth naming: advancing contiguously gives up noticing a
     * single day deleted by hand from the middle of summarised history. That is
     * repaired by clearing {@code daily_ticket_stats} from the damaged day
     * forward and letting backfill rebuild it, rather than by making every pass
     * for ever scan every historical date against the table.
     */
    Optional<LocalDate> backfillResumePoint(LocalDate earliest, LocalDate before) {
        LocalDate lastFilled = jdbc.sql(
                        "SELECT MAX(stat_date) FROM daily_ticket_stats WHERE stat_date < :before")
                .param("before", before)
                .query(LocalDate.class)
                .optional()
                .orElse(null);

        // Never before the first real activity: rows left by an earlier run
        // over tickets since deleted would otherwise drag the resume point
        // back into dates with nothing to summarise, every pass, for ever.
        LocalDate from = lastFilled == null ? earliest : latestOf(lastFilled.plusDays(1), earliest);
        return from.isBefore(before) ? Optional.of(from) : Optional.empty();
    }

    private static LocalDate latestOf(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    /**
     * Recompute {@code daily_ticket_stats} for one date, every project.
     *
     * <p>Projects with no tickets still get a row of zeroes: the dashboard's
     * project filter should show an honest empty chart rather than nothing at
     * all, and "no row" and "no tickets" are otherwise the same absence.
     */
    int refreshTicketStats(LocalDate day, Instant computedAt) {
        return jdbc.sql("""
                INSERT INTO daily_ticket_stats (
                    stat_date, project_id, created, closed, reopened,
                    open_total, open_critical, open_high, open_medium, open_low,
                    open_delayed, open_reopened,
                    aging_0_2, aging_3_7, aging_8_30, aging_31_plus,
                    sla_closed, sla_met, type_counts, computed_at)
                SELECT
                    :day, p.id,
                    -- flow: bounded by the day itself
                    COALESCE(SUM(t.date_reported     >= :dayStart AND t.date_reported     < :dayEnd), 0),
                    COALESCE(SUM(t.actual_close_date >= :dayStart AND t.actual_close_date < :dayEnd), 0),
                    COALESCE((SELECT COUNT(*) FROM ticket_cycles c
                                JOIN tickets ct ON ct.id = c.ticket_id
                               WHERE ct.project_id = p.id AND c.cycle_no >= 2
                                 AND c.start_date >= :dayStart AND c.start_date < :dayEnd), 0),
                    -- stock: what was still open when the day ended
                    COALESCE(SUM(o.open_at_eod), 0),
                    COALESCE(SUM(o.open_at_eod AND t.level = 'CRITICAL'), 0),
                    COALESCE(SUM(o.open_at_eod AND t.level = 'HIGH'), 0),
                    COALESCE(SUM(o.open_at_eod AND t.level = 'MEDIUM'), 0),
                    COALESCE(SUM(o.open_at_eod AND t.level = 'LOW'), 0),
                    -- derived, not the is_delayed flag: that is current state
                    COALESCE(SUM(o.open_at_eod AND t.planned_close_date < :dayEnd), 0),
                    COALESCE(SUM(o.open_at_eod AND EXISTS (
                        SELECT 1 FROM ticket_cycles rc
                         WHERE rc.ticket_id = t.id AND rc.cycle_no >= 2
                           AND rc.start_date < :dayEnd)), 0),
                    -- aging measured at the end of the day, in whole days
                    COALESCE(SUM(o.open_at_eod AND DATEDIFF(:day, DATE(t.date_reported)) <= 2), 0),
                    COALESCE(SUM(o.open_at_eod AND DATEDIFF(:day, DATE(t.date_reported)) BETWEEN 3 AND 7), 0),
                    COALESCE(SUM(o.open_at_eod AND DATEDIFF(:day, DATE(t.date_reported)) BETWEEN 8 AND 30), 0),
                    COALESCE(SUM(o.open_at_eod AND DATEDIFF(:day, DATE(t.date_reported)) > 30), 0),
                    -- A-057 · widget 14. Flow, bounded by the day like `closed`
                    -- above and for the same reason: compliance is a property of
                    -- work *finished*, not of what is currently late.
                    --
                    -- The denominator excludes tickets with no
                    -- planned_close_date — no commitment was made, so there is
                    -- nothing to meet or breach, and counting them either way
                    -- moves a percentage nobody promised.
                    COALESCE(SUM(t.actual_close_date >= :dayStart
                                 AND t.actual_close_date < :dayEnd
                                 AND t.planned_close_date IS NOT NULL), 0),
                    -- `<=`, not `<`: closing exactly on the committed date is
                    -- meeting the commitment, and DATETIME(6) makes the
                    -- boundary a real case rather than a theoretical one.
                    COALESCE(SUM(t.actual_close_date >= :dayStart
                                 AND t.actual_close_date < :dayEnd
                                 AND t.planned_close_date IS NOT NULL
                                 AND t.actual_close_date <= t.planned_close_date), 0),
                    NULL,   -- type_counts, filled by the statement below
                    :computedAt
                FROM projects p
                LEFT JOIN tickets t ON t.project_id = p.id
                LEFT JOIN LATERAL (
                    SELECT (t.date_reported < :dayEnd
                            AND (t.actual_close_date IS NULL OR t.actual_close_date >= :dayEnd))
                           AS open_at_eod
                ) o ON TRUE
                GROUP BY p.id
                ON DUPLICATE KEY UPDATE
                    created = VALUES(created), closed = VALUES(closed), reopened = VALUES(reopened),
                    open_total = VALUES(open_total), open_critical = VALUES(open_critical),
                    open_high = VALUES(open_high), open_medium = VALUES(open_medium),
                    open_low = VALUES(open_low), open_delayed = VALUES(open_delayed),
                    open_reopened = VALUES(open_reopened),
                    aging_0_2 = VALUES(aging_0_2), aging_3_7 = VALUES(aging_3_7),
                    aging_8_30 = VALUES(aging_8_30), aging_31_plus = VALUES(aging_31_plus),
                    -- A-057. Easy to add to the INSERT list and forget here,
                    -- and the failure is silent: the INSERT branch runs once
                    -- per (day, project), so every subsequent recompute would
                    -- take the UPDATE branch and leave these two NULL for ever
                    -- on exactly the days the worker revisits most.
                    sla_closed = VALUES(sla_closed), sla_met = VALUES(sla_met),
                    type_counts = VALUES(type_counts),
                    computed_at = VALUES(computed_at)
                """)
                .param("day", day)
                .param("dayStart", day.atStartOfDay())
                .param("dayEnd", day.plusDays(1).atStartOfDay())
                .param("computedAt", computedAt)
                .update();
    }

    /**
     * A-056 · the task-type breakdown §S-05's donut reads, as a second pass.
     *
     * <h2>Why this is not a column in the statement above</h2>
     *
     * <p>It was, and it deadlocked. {@code INSERT … SELECT} takes shared locks
     * on every row it reads from {@code tickets}, and a correlated subquery
     * re-reading {@code tickets} from inside that same statement contends with
     * the locks the statement is already holding — {@code CannotAcquireLock},
     * on a suite that passed the day before. Splitting the read out of the
     * insert removes the contention entirely.
     *
     * <p>The cost is a second statement per day. That is the right trade: the
     * alternative is one clever statement that works at fixture scale and times
     * out under A-073's 50,000 tickets, which is exactly the failure these
     * summary tables exist to prevent.
     *
     * <h2>The shape, and what absence means</h2>
     *
     * <p>{@code {"3": 41}} — task_type_id to <b>open</b> count, matching
     * {@code open_total} and the level columns rather than counting creations.
     * A type with nothing open is absent rather than zero: a donut draws no
     * slice for a type nobody raised, and eleven zero entries per project per
     * day would be most of the column. A project with nothing open at all keeps
     * NULL, because {@code '{}'} would claim no type had anything open, and
     * NULL says the question does not arise.
     */
    int refreshTypeCounts(LocalDate day, Instant computedAt) {
        return jdbc.sql("""
                UPDATE daily_ticket_stats s
                   JOIN (SELECT t.project_id,
                                JSON_OBJECTAGG(t.task_type_id, t.open_count) AS counts
                           FROM (SELECT project_id, task_type_id, COUNT(*) AS open_count
                                   FROM tickets
                                  WHERE task_type_id IS NOT NULL
                                    AND date_reported < :dayEnd
                                    AND (actual_close_date IS NULL OR actual_close_date >= :dayEnd)
                                  GROUP BY project_id, task_type_id) t
                          GROUP BY t.project_id) byProject
                     ON byProject.project_id = s.project_id
                    SET s.type_counts = byProject.counts
                 WHERE s.stat_date = :day
                """)
                .param("day", day)
                .param("dayEnd", day.plusDays(1).atStartOfDay())
                .update();
    }

    /**
     * Recompute {@code resource_daily_stats} for one date.
     *
     * <p>Only users who did something or hold something are given a row —
     * unlike projects above. An organisation has far more people than
     * projects, and a row of zeroes for every inactive resource on every day
     * would be most of the table.
     *
     * <h2>Cleared and rewritten, not upserted over</h2>
     *
     * <p>Because a user <em>earns</em> a row here, a user can also stop
     * earning one, and an upsert has no way to retract what it wrote last
     * time. The common way that happens is reassignment: this table is
     * computed against {@code tickets.assigned_to}, which carries no history
     * (see the class note), so moving a ticket changes who qualified on days
     * already summarised. Upserting would leave the previous assignee's row
     * behind untouched, and the resource dashboard would go on crediting them
     * with a ticket that moved months ago — while the new assignee's row shows
     * the same ticket. The figure would not merely be stale, it would be
     * double-counted across two people.
     *
     * <p>{@code daily_ticket_stats} needs no equivalent: every project gets a
     * row on every day by construction, so there is never a row to retract.
     *
     * <p><b>Public deliberately.</b> Spring's transaction attribute source
     * ignores {@code @Transactional} on non-public methods and says nothing
     * when it does, which would leave the DELETE and the INSERT in separate
     * autocommitted statements — and every dashboard reading that day in the
     * gap between them would see the resource widgets empty. The class itself
     * is package-private, so this widens nothing outside {@code stats}.
     */
    @Transactional
    public int refreshResourceStats(LocalDate day, Instant computedAt) {
        jdbc.sql("DELETE FROM resource_daily_stats WHERE stat_date = :day")
                .param("day", day)
                .update();

        return jdbc.sql("""
                INSERT INTO resource_daily_stats (
                    stat_date, user_id, closed, effort_hours,
                    assigned_open, assigned_critical, assigned_delayed,
                    assigned_in_progress,
                    assigned_aging_0_2, assigned_aging_3_7,
                    assigned_aging_8_30, assigned_aging_31_plus,
                    assigned_due_today, assigned_due_next_7,
                    computed_at)
                SELECT :day, u.id,
                    COALESCE(c.closed, 0),
                    COALESCE(e.hours, 0),
                    COALESCE(a.open_count, 0),
                    COALESCE(a.critical_count, 0),
                    COALESCE(a.delayed_count, 0),
                    COALESCE(a.in_progress_count, 0),
                    COALESCE(a.aging_0_2, 0),
                    COALESCE(a.aging_3_7, 0),
                    COALESCE(a.aging_8_30, 0),
                    COALESCE(a.aging_31_plus, 0),
                    COALESCE(a.due_today, 0),
                    COALESCE(a.due_next_7, 0),
                    :computedAt
                FROM users u
                LEFT JOIN (
                    SELECT assigned_to AS uid, COUNT(*) AS closed
                      FROM tickets
                     WHERE actual_close_date >= :dayStart AND actual_close_date < :dayEnd
                       AND assigned_to IS NOT NULL
                     GROUP BY assigned_to
                ) c ON c.uid = u.id
                LEFT JOIN (
                    -- work_date, not logged_at: §4A.4 attributes hours to the day
                    -- the work happened, so a timesheet filled in on Friday for
                    -- Monday belongs on Monday — and changes Monday's row when
                    -- it is next recomputed.
                    SELECT user_id AS uid, SUM(hours) AS hours
                      FROM ticket_effort_logs
                     WHERE work_date = :day
                     GROUP BY user_id
                ) e ON e.uid = u.id
                LEFT JOIN (
                    SELECT assigned_to AS uid,
                           COUNT(*) AS open_count,
                           SUM(level = 'CRITICAL') AS critical_count,
                           SUM(planned_close_date < :dayEnd) AS delayed_count,
                           -- A-056 · widget 10's middle segment. The
                           -- `NOT (planned_close_date < :dayEnd)` is what keeps
                           -- the three segments disjoint, and it is the whole
                           -- reason this is not simply `status IN (…)`: widget
                           -- 10 stacks them, so a ticket that is both delayed
                           -- and being worked would be drawn in two segments
                           -- and the bar would overstate the person's load.
                           -- The migration header carries the full argument.
                           --
                           -- `planned_close_date` may be NULL — a ticket with
                           -- no due date cannot be delayed — and NULL < x is
                           -- NULL, not false, so the comparison is wrapped in
                           -- COALESCE rather than negated directly. Without it
                           -- SUM() skips those rows and every ticket without a
                           -- due date vanishes from the bar entirely.
                           SUM(status IN ('IN_PROGRESS', 'REWORK')
                               AND NOT COALESCE(planned_close_date < :dayEnd, FALSE))
                               AS in_progress_count,
                           -- A-062 · widget 12 per resource. The same four
                           -- edges and the same DATEDIFF as the project table's
                           -- aging columns in refreshTicketStats above, because
                           -- a Developer and their PM read two charts with the
                           -- same labels and the same drill-down links; edges
                           -- that differ by one day between them produce two
                           -- figures that will not reconcile and no way to see
                           -- why. Measured at the end of the day, in whole
                           -- days, against the day being summarised — never
                           -- against the clock, or recomputing an old day would
                           -- age every ticket in it to today.
                           SUM(DATEDIFF(:day, DATE(date_reported)) <= 2) AS aging_0_2,
                           SUM(DATEDIFF(:day, DATE(date_reported)) BETWEEN 3 AND 7) AS aging_3_7,
                           SUM(DATEDIFF(:day, DATE(date_reported)) BETWEEN 8 AND 30) AS aging_8_30,
                           SUM(DATEDIFF(:day, DATE(date_reported)) > 30) AS aging_31_plus,
                           -- A-062 · "my due today / this week". Due is what is
                           -- *coming*, so both are bounded below by the start of
                           -- the day and exclude everything delayed_count above
                           -- has already counted. A ticket with no
                           -- planned_close_date is in neither: nothing was
                           -- committed, so nothing is due.
                           --
                           -- >= :dayStart rather than a DATE() equality, so the
                           -- DATETIME(6) column is compared as a half-open
                           -- range and the index on it stays usable.
                           SUM(planned_close_date >= :dayStart
                               AND planned_close_date < :dayEnd) AS due_today,
                           -- Seven days *including* today — see the migration
                           -- header. due_today is a subset of this by
                           -- construction, which is the containment the two
                           -- labels claim.
                           SUM(planned_close_date >= :dayStart
                               AND planned_close_date < :weekEnd) AS due_next_7
                      FROM tickets
                     WHERE assigned_to IS NOT NULL
                       AND date_reported < :dayEnd
                       AND (actual_close_date IS NULL OR actual_close_date >= :dayEnd)
                     GROUP BY assigned_to
                ) a ON a.uid = u.id
                WHERE c.uid IS NOT NULL OR e.uid IS NOT NULL OR a.uid IS NOT NULL
                """)
                .param("day", day)
                .param("dayStart", day.atStartOfDay())
                .param("dayEnd", day.plusDays(1).atStartOfDay())
                // A-062 · the exclusive upper bound of "today plus six more
                // days". plusDays(7) rather than plusDays(6), because the
                // comparison is `<` against the start of a day: a ticket due at
                // 17:00 on the seventh day is inside the week and `< day+6`
                // would drop it.
                .param("weekEnd", day.plusDays(7).atStartOfDay())
                .param("computedAt", computedAt)
                .update();
    }

    /**
     * A-059 · recompute {@code client_daily_stats} for one date — §S-05's
     * widget 20 and, later, A-068's client report.
     *
     * <h2>Cleared and rewritten, not upserted over</h2>
     *
     * <p>The same argument {@link #refreshResourceStats} makes, and it applies
     * here for a mutable column rather than a missing history table. A
     * (project, client) pair <em>earns</em> its row by having something to
     * report, so a pair can stop earning one: {@code tickets.client_id} is
     * editable, and re-attributing a ticket — a support desk correcting the
     * client on a mis-filed ticket, which is routine — changes who qualified on
     * every day already summarised. An upsert cannot retract what it wrote, so
     * the old client would keep its count while the new client gained the same
     * ticket, and the bar chart would show that ticket twice under two names.
     * {@code daily_ticket_stats} needs no equivalent, because every project
     * gets a row on every day by construction.
     *
     * <p><b>Public deliberately</b>, for the reason spelled out on
     * {@link #refreshResourceStats}: Spring's transaction attribute source
     * ignores {@code @Transactional} on non-public methods and says nothing when
     * it does, which would leave the DELETE and the INSERT autocommitted
     * separately — and every dashboard reading that day in the gap between them
     * would draw an empty chart. The class is package-private, so this widens
     * nothing outside {@code stats}.
     *
     * <h2>Only client-attributed tickets</h2>
     *
     * <p>{@code client_id IS NOT NULL}: an internally-raised ticket belongs to
     * no client and there is no bar for it to sit in. That makes this table a
     * breakdown of client-attributed work rather than of all work, which is
     * what "client-wise" asks for — and it is why the widget's total can be
     * legitimately smaller than the KPI cards'.
     */
    @Transactional
    public int refreshClientStats(LocalDate day, Instant computedAt) {
        jdbc.sql("DELETE FROM client_daily_stats WHERE stat_date = :day")
                .param("day", day)
                .update();

        return jdbc.sql("""
                INSERT INTO client_daily_stats (
                    stat_date, project_id, client_id, created, closed, open_total, computed_at)
                SELECT :day, t.project_id, t.client_id,
                    -- flow, bounded by the day itself
                    COALESCE(SUM(t.date_reported >= :dayStart
                                 AND t.date_reported < :dayEnd), 0) AS created_in_day,
                    -- COALESCE is load-bearing here rather than house style:
                    -- actual_close_date is NULL on an open ticket, `NULL < x`
                    -- is NULL rather than false, and SUM skips NULLs — so a
                    -- client holding nothing but open tickets sums to NULL, not
                    -- to zero, and the NOT NULL column rejects the row. That
                    -- client would then be missing from the chart entirely, for
                    -- the offence of having closed nothing.
                    COALESCE(SUM(t.actual_close_date >= :dayStart
                                 AND t.actual_close_date < :dayEnd), 0) AS closed_in_day,
                    -- stock: still open when the day ended. Deliberately the
                    -- same predicate refreshTicketStats uses, so a client's
                    -- open count and its projects' open counts are computed by
                    -- one definition and reconcile.
                    COALESCE(SUM(t.date_reported < :dayEnd
                                 AND (t.actual_close_date IS NULL
                                      OR t.actual_close_date >= :dayEnd)), 0) AS open_at_eod,
                    :computedAt
                FROM tickets t
                WHERE t.client_id IS NOT NULL
                  -- A ticket raised after this day cannot have been created in
                  -- it, open at the end of it, or closed during it. Excluded
                  -- here rather than summed to zero three times.
                  AND t.date_reported < :dayEnd
                GROUP BY t.project_id, t.client_id
                -- A pair with nothing to report earns no row: reported before
                -- the day and closed before it too. Without this every client
                -- that has ever existed would get a row of zeroes on every day
                -- for ever, and the table would grow by clients times projects
                -- times days regardless of activity.
                HAVING created_in_day > 0 OR closed_in_day > 0 OR open_at_eod > 0
                """)
                .param("day", day)
                .param("dayStart", day.atStartOfDay())
                .param("dayEnd", day.plusDays(1).atStartOfDay())
                .param("computedAt", computedAt)
                .update();
    }

    // ── A-058 · §S-05 widgets 16–19, the four the ribbon unlocks ─────────────

    /**
     * A-058 · widget 16 — fills {@code daily_ticket_stats.wip_by_stage}, the
     * column A-050 declared and left NULL against this task by name.
     *
     * <h2>🔴 Read from the transitions, not from {@code tickets.current_stage}</h2>
     *
     * <p>This is the decision the whole column turns on, and the obvious
     * implementation is the wrong one.
     *
     * <p>{@code tickets.current_stage} is current state with no history. A pass
     * reading it would write <em>today's</em> stage distribution into every day
     * it recomputes — and because {@link StatsRefreshWorker} recomputes a
     * trailing week on every pass, last Tuesday's funnel would silently become
     * a copy of this morning's, five minutes at a time. The chart would look
     * entirely plausible while being a claim about the wrong day.
     *
     * <p>{@code ticket_stage_transitions} carries {@code entered_at} and
     * {@code exited_at} on every hop, so "which stage was this ticket in at the
     * end of 3 August" is answerable exactly: the visit that had begun by then
     * and had not ended. That makes this column <b>faithfully historical</b> —
     * the class note's first category rather than its second — and A-050's
     * stated reason for declaring it early ("a stock column cannot be
     * backfilled") turns out not to bind here after all. It was written before
     * Stream C's transitions existed; the column is genuinely recoverable now,
     * and history fills in rather than starting from the day this ships.
     *
     * <h2>Closed tickets sit in no stage</h2>
     *
     * <p>Restricted to tickets still open at the end of the day, matching
     * A-067's stage-funnel report, which counts {@code status <> 'CLOSED'}.
     * The two must not disagree: they are the same question asked on the
     * dashboard and in a report, and a manager who opens both should not have
     * to work out which is lying. It also keeps the funnel readable — the
     * terminal stage accumulates for ever and would dwarf every bar in front of
     * it.
     *
     * <h2>The shape, and what absence means</h2>
     *
     * <p>{@code {"QA": 7}} — stage code to open count, the same shape and the
     * same reasoning as {@code type_counts}: a stage with nothing in it is
     * absent rather than zero, because a funnel draws no band for an empty
     * stage. A project with nothing open keeps NULL, and NULL says the question
     * does not arise rather than claiming every stage is empty.
     *
     * <p><b>A LEFT JOIN, so a project that has emptied is reset.</b>
     * {@code refreshTypeCounts} above uses an inner join and therefore leaves
     * yesterday's document in place for a project that no longer matches — a
     * latent staleness this method must not copy, because a funnel is read as
     * "where the work is now" and a stale one points at a bottleneck that has
     * already cleared.
     */
    int refreshWipByStage(LocalDate day, Instant computedAt) {
        return jdbc.sql("""
                UPDATE daily_ticket_stats s
                   LEFT JOIN (SELECT g.project_id,
                                     JSON_OBJECTAGG(g.stage_code, g.wip) AS wip
                                FROM (SELECT t.project_id,
                                             v.to_stage AS stage_code,
                                             COUNT(*)   AS wip
                                        FROM tickets t
                                        JOIN ticket_stage_transitions v
                                          -- The ticket's latest hop begun by the
                                          -- end of the day. MAX(id) rather than
                                          -- MAX(seq_no): seq_no restarts at 1 on
                                          -- every cycle, so a reopened ticket has
                                          -- two rows numbered 1 and the greater
                                          -- seq_no can belong to the older cycle.
                                          ON v.id = (SELECT MAX(x.id)
                                                       FROM ticket_stage_transitions x
                                                      WHERE x.ticket_id = t.id
                                                        AND x.entered_at < :dayEnd)
                                       WHERE t.date_reported < :dayEnd
                                         AND (t.actual_close_date IS NULL
                                              OR t.actual_close_date >= :dayEnd)
                                         -- Still in that stage when the day
                                         -- ended. A hop sealed before then is a
                                         -- ticket that had left and not yet
                                         -- arrived anywhere: counted in no band,
                                         -- which is the truth about it.
                                         AND (v.exited_at IS NULL OR v.exited_at >= :dayEnd)
                                       GROUP BY t.project_id, v.to_stage) g
                               GROUP BY g.project_id) byProject
                     ON byProject.project_id = s.project_id
                    SET s.wip_by_stage = byProject.wip
                 WHERE s.stat_date = :day
                """)
                .param("day", day)
                .param("dayEnd", day.plusDays(1).atStartOfDay())
                .update();
    }

    /**
     * A-058 · widget 17 — how many open tickets are being reworked, and how many
     * are ping-ponging.
     *
     * <h2>The two counters are not the same counter, and this is where that
     * gets got wrong</h2>
     *
     * <p>{@code iteration_no} increments on a backward move inside a cycle;
     * {@code cycle_no} increments when a closed ticket is reopened. The
     * baseline migration calls this "the single most misread concept in the
     * spec". Widget 17 is about {@code iteration_no} only — a ticket reopened
     * three times but resolved cleanly each time is not being reworked, and
     * counting it here would put a well-run team on a quality warning.
     *
     * <p>So the iteration is taken <b>within the ticket's latest cycle</b> as of
     * that day. A reopen starts a fresh journey with iteration back at 1, and a
     * MAX over every cycle would carry a bounce from six months ago into a
     * cycle that has been clean since. The join to {@code latestCycle} is what
     * expresses that; without it the query still runs and still returns
     * plausible numbers.
     *
     * <h2>Two thresholds, and the second is a strict subset</h2>
     *
     * <p>§7.9 puts widget 17 at {@code >= 2} — sent back at least once. §4A.7
     * raises the ping-pong alert at {@code >= 3}, where one correction becomes
     * a loop. {@code pingpong_open} is therefore always {@code <= rework_open},
     * and the widget draws them nested rather than side by side.
     *
     * <h2>Open at end of day, and so never summed over days</h2>
     *
     * <p>Stock, like every column it sits beside. The predicate is the one
     * {@link #refreshTicketStats} uses for {@code open_total}, deliberately, so
     * "12 of 80 open tickets are in rework" reconciles against the KPI card
     * beside it rather than being two different populations that happen to
     * share a screen.
     */
    int refreshReworkCounts(LocalDate day, Instant computedAt) {
        return jdbc.sql("""
                UPDATE daily_ticket_stats s
                   LEFT JOIN (SELECT t.project_id,
                                     SUM(it.max_iteration >= 2) AS rework,
                                     SUM(it.max_iteration >= 3) AS pingpong
                                FROM tickets t
                                JOIN (SELECT tr.ticket_id,
                                             MAX(tr.iteration_no) AS max_iteration
                                        FROM ticket_stage_transitions tr
                                        JOIN (SELECT ticket_id,
                                                     MAX(cycle_no) AS cycle_no
                                                FROM ticket_stage_transitions
                                               WHERE entered_at < :dayEnd
                                            GROUP BY ticket_id) latestCycle
                                          ON latestCycle.ticket_id = tr.ticket_id
                                         AND latestCycle.cycle_no  = tr.cycle_no
                                       WHERE tr.entered_at < :dayEnd
                                    GROUP BY tr.ticket_id) it
                                  ON it.ticket_id = t.id
                               WHERE t.date_reported < :dayEnd
                                 AND (t.actual_close_date IS NULL
                                      OR t.actual_close_date >= :dayEnd)
                            GROUP BY t.project_id) r
                     ON r.project_id = s.project_id
                    -- COALESCE, and it is load-bearing: a project whose tickets
                    -- have all stopped bouncing matches no row here, and without
                    -- this it would keep yesterday's count for ever on a NOT NULL
                    -- column — a quality warning that can be earned and never
                    -- cleared.
                    SET s.rework_open   = COALESCE(r.rework, 0),
                        s.pingpong_open = COALESCE(r.pingpong, 0)
                 WHERE s.stat_date = :day
                """)
                .param("day", day)
                .param("dayEnd", day.plusDays(1).atStartOfDay())
                .update();
    }

    /**
     * A-058 · widgets 18 and 19 — one row per stage per project per day.
     *
     * <h2>Cleared and rewritten, not upserted over</h2>
     *
     * <p>{@link #refreshClientStats}' argument, for a different mutable input.
     * A (project, stage) pair earns its row by having ribbon activity, and it
     * can stop earning one: {@code V20260818_2140} deprecates stage codes and
     * B-019's templates can be re-pointed, so a stage that saw traffic on a day
     * already summarised can cease to exist. An upsert cannot retract what it
     * wrote, and widget 18 would go on drawing a bar for a stage no workflow
     * defines.
     *
     * <p><b>Public deliberately</b>, for the reason spelled out on
     * {@link #refreshResourceStats}: Spring ignores {@code @Transactional} on a
     * non-public method and says nothing when it does, which would autocommit
     * the DELETE and the INSERT separately and leave every dashboard reading
     * that day in between with an empty chart.
     *
     * <h2>Four measures from three sources, unioned rather than joined</h2>
     *
     * <p>Entries, exits, effort and handoffs are counted over four different
     * populations — hops that <em>began</em> that day, hops that <em>ended</em>
     * that day, effort attributed to that {@code work_date}, and the gaps
     * before the hops that began. Joining them would multiply rows against each
     * other; a {@code UNION ALL} of four single-purpose selects each
     * contributing zero to the columns it does not measure sums cleanly and
     * stays readable one branch at a time.
     *
     * <p>The handoff branch is absent from the statement — it needs the working
     * calendar, so it is applied afterwards by {@link #applyHandoffLatency}.
     */
    @Transactional
    public int refreshStageStats(LocalDate day, Instant computedAt) {
        jdbc.sql("DELETE FROM stage_daily_stats WHERE stat_date = :day")
                .param("day", day)
                .update();

        int written = jdbc.sql("""
                INSERT INTO stage_daily_stats (
                    stat_date, project_id, stage_code,
                    entered, exited, elapsed_mins, active_mins,
                    handoff_count, handoff_mins, computed_at)
                SELECT :day, f.project_id, f.stage_code,
                       SUM(f.entered), SUM(f.exited),
                       SUM(f.elapsed_mins), SUM(f.active_mins),
                       0, 0, :computedAt
                  FROM (
                        -- Hops that BEGAN in the day.
                        SELECT t.project_id, v.to_stage AS stage_code,
                               1 AS entered, 0 AS exited,
                               0 AS elapsed_mins, 0 AS active_mins
                          FROM ticket_stage_transitions v
                          JOIN tickets t ON t.id = v.ticket_id
                         WHERE v.entered_at >= :dayStart AND v.entered_at < :dayEnd

                        UNION ALL

                        -- Visits SEALED in the day. A separate population from
                        -- the one above on purpose: a stage entered on Monday
                        -- and left on Thursday is one entry and one exit on two
                        -- different days, and widget 18 divides these minutes by
                        -- THIS count rather than by arrivals.
                        --
                        -- COALESCE because duration_mins is nullable: A-008
                        -- permits sealing to set exited_at and duration_mins,
                        -- and a row sealed by anything that set only the former
                        -- would otherwise make the whole SUM NULL and be
                        -- rejected by the NOT NULL column — losing every other
                        -- visit to that stage that day along with it.
                        SELECT t.project_id, v.to_stage, 0, 1,
                               COALESCE(v.duration_mins, 0), 0
                          FROM ticket_stage_transitions v
                          JOIN tickets t ON t.id = v.ticket_id
                         WHERE v.exited_at >= :dayStart AND v.exited_at < :dayEnd

                        UNION ALL

                        -- Effort logged AGAINST that work_date, per §4A.4 — a
                        -- timesheet filled in on Friday for Monday's work
                        -- belongs to Monday, which is why the worker recomputes
                        -- a trailing week rather than only today.
                        --
                        -- Corrections are SUMMED, not excluded: a correcting row
                        -- carries a signed value cancelling the entry it
                        -- reverses, so filtering is_correction out would restore
                        -- the mistake it exists to undo.
                        SELECT t.project_id, e.stage_code, 0, 0, 0,
                               ROUND(SUM(e.hours) * 60)
                          FROM ticket_effort_logs e
                          JOIN tickets t ON t.id = e.ticket_id
                         WHERE e.work_date = :day
                           AND e.stage_code IS NOT NULL
                      GROUP BY t.project_id, e.stage_code
                       ) f
              GROUP BY f.project_id, f.stage_code
                """)
                .param("day", day)
                .param("dayStart", day.atStartOfDay())
                .param("dayEnd", day.plusDays(1).atStartOfDay())
                .param("computedAt", computedAt)
                .update();

        applyHandoffLatency(day);
        return written;
    }

    /**
     * A-058 · widget 19's two columns, computed through the working calendar and
     * folded into the rows {@link #refreshStageStats} has just written.
     *
     * <h2>Why this is a Java loop and not another UNION branch</h2>
     *
     * <p>Because CLAUDE.md says duration maths uses the working calendar, and
     * the calendar is three tables and a weekly-off pattern that SQL in this
     * repository has no way to consult. {@code TIMESTAMPDIFF} would report a
     * Friday-evening handoff picked up at nine on Monday as two days of queue
     * waste. Widget 19 exists to point at queue waste, so that particular wrong
     * answer is the one it must not give.
     *
     * <p><b>The cost is real and is worth naming.</b> One
     * {@link WorkingHoursService#workingHoursBetween} call per handoff, each
     * reading holidays and the calendar row, over a seven-day trailing window
     * every five minutes. {@code StaleTicketNudge} already calls it per ticket
     * in a loop, so the shape has precedent — but this is the first per-hop
     * use, and A-073 owns performance and should look here.
     *
     * <h2>Attributed to the receiving stage</h2>
     *
     * <p>The gap belongs to the queue the ticket was waiting in, not to the
     * team that finished. Charging it to the sending stage would penalise
     * whoever did their job for the time the next team took to start, which
     * inverts the reading the chart is for.
     *
     * <h2>Negative gaps are dropped, not clamped to zero</h2>
     *
     * <p>{@code entered_at} before the previous {@code exited_at} means the two
     * rows disagree about time — a seal written after the next hop began. That
     * is a defect in whatever wrote them and not a zero-length handoff, and
     * counting it as zero would quietly pull the average down and take the
     * evidence with it. Dropped from both the count and the total, so the mean
     * stays a mean of intervals that actually happened.
     */
    private void applyHandoffLatency(LocalDate day) {
        List<Handoff> handoffs = jdbc.sql("""
                SELECT t.project_id     AS project_id,
                       v.to_stage       AS stage_code,
                       prev.exited_at   AS left_at,
                       v.entered_at     AS arrived_at
                  FROM ticket_stage_transitions v
                  JOIN tickets t ON t.id = v.ticket_id
                  -- The immediately preceding hop for the same ticket. By id
                  -- rather than by seq_no, which restarts on every cycle — the
                  -- first hop of cycle 2 follows the last hop of cycle 1 and has
                  -- the lower seq_no of the two.
                  JOIN ticket_stage_transitions prev
                    ON prev.id = (SELECT MAX(p.id)
                                    FROM ticket_stage_transitions p
                                   WHERE p.ticket_id = v.ticket_id
                                     AND p.id < v.id)
                 WHERE v.entered_at >= :dayStart AND v.entered_at < :dayEnd
                   -- An unsealed previous hop means the ticket never left it, so
                   -- there is no gap to measure — and a row in that state
                   -- alongside a later arrival is itself a defect for the hash
                   -- verifier rather than a latency for this chart.
                   AND prev.exited_at IS NOT NULL
                   AND v.entered_at >= prev.exited_at
                """)
                .param("dayStart", day.atStartOfDay())
                .param("dayEnd", day.plusDays(1).atStartOfDay())
                .query((rs, n) -> new Handoff(
                        rs.getLong("project_id"),
                        rs.getString("stage_code"),
                        rs.getObject("left_at", java.time.LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        rs.getObject("arrived_at", java.time.LocalDateTime.class).toInstant(ZoneOffset.UTC)))
                .list();

        if (handoffs.isEmpty()) {
            return;
        }

        // Accumulated per (project, stage) before writing, so one UPDATE lands
        // per row of the table rather than one per handoff.
        Map<Handoff.Key, long[]> totals = new LinkedHashMap<>();
        for (Handoff handoff : handoffs) {
            BigDecimal hours = workingHours.workingHoursBetween(
                    handoff.leftAt(), handoff.arrivedAt(), handoff.projectId(), null);
            long minutes = hours.multiply(BigDecimal.valueOf(60))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();

            long[] running = totals.computeIfAbsent(handoff.key(), key -> new long[2]);
            running[0]++;
            running[1] += minutes;
        }

        // One statement per ROW OF THE TABLE, not per handoff. A busy day is
        // hundreds of hops but only ever projects × stages rows, and that is the
        // bound this loop runs against — the accumulation above is what turns
        // the former into the latter.
        totals.forEach((key, running) -> jdbc.sql("""
                        UPDATE stage_daily_stats
                           SET handoff_count = :count, handoff_mins = :minutes
                         WHERE stat_date = :day
                           AND project_id = :projectId
                           AND stage_code = :stageCode
                        """)
                .param("count", running[0])
                .param("minutes", running[1])
                .param("day", day)
                .param("projectId", key.projectId())
                .param("stageCode", key.stageCode())
                .update());
    }

    // ── Dashboard Rework Dev 2, PR 14 · the module-open widget's table ───────

    /**
     * Fills {@code module_daily_stats} — module-wise open tickets, split three
     * ways for §S-05's {@code module-open} bar.
     *
     * <h2>The three segments are disjoint, and overdue takes precedence</h2>
     *
     * <p>This is the decision the whole widget turns on and the obvious
     * implementation gets it wrong. A stacked bar makes an arithmetic claim —
     * the segments add up to the whole — so a ticket that is both overdue and
     * in progress must be counted once. Three independent {@code SUM}s over
     * overlapping predicates would count it twice, every module's bar would
     * overstate its load in proportion to how late that module is running, and
     * nobody would notice for a month because each segment is individually
     * plausible.
     *
     * <p>The {@code CASE} below is what makes it a partition rather than three
     * counts: one arm per ticket, overdue tested first. The migration header
     * carries the same rule, so the shape is stated where the table is defined
     * and enforced where the rows are written.
     *
     * <h2>Category, not status code</h2>
     *
     * <p>Joined to {@code statuses} rather than matching codes inline. B-039
     * made {@code category} a column precisely because it is not derivable —
     * {@code ON_HOLD}, {@code AWAITING_INFO} and {@code REWORK} are all
     * IN_PROGRESS while carrying the same two booleans as {@code NEW} — and an
     * organisation may add statuses. A hardcoded list would silently drop a new
     * status out of every segment.
     *
     * <p>Category DONE is excluded, which drops RESOLVED-not-CLOSED: work that
     * is finished with its record still open. S-05 counts those on the Today
     * tab's Pending Review card. Including them here would put finished work in
     * a chart titled "open tickets" and in none of the three segments, so the
     * bar would stop summing to its own total.
     *
     * <h2>Stock, computed at the end of the day</h2>
     *
     * <p>Every column here is stock and none of it backfills — see the
     * migration. The table therefore starts empty and fills forward, and a
     * chart blank for its first days is correct rather than broken.
     *
     * <p>The open predicate is deliberately the one {@link #refreshTicketStats}
     * and {@link #refreshClientStats} already use, so a module's open count and
     * its projects' open counts are computed by one definition and reconcile.
     *
     * <h2>The day is deleted and rewritten</h2>
     *
     * <p>{@link #refreshClientStats}' argument, for another mutable input: a
     * (project, module) pair earns its row by having open work and can stop
     * earning one, because §7.5's {@code module_id} is editable on the ticket.
     * An upsert cannot retract what it wrote, so a re-pointed ticket would
     * stand in two modules' bars at once.
     *
     * <p><b>Public deliberately</b>, for the reason spelled out on
     * {@link #refreshResourceStats}: Spring's transaction attribute source
     * ignores {@code @Transactional} on a non-public method and says nothing
     * when it does, which would autocommit the DELETE and the INSERT separately
     * and leave every dashboard reading that day in between with an empty chart.
     */
    @Transactional
    public int refreshModuleStats(LocalDate day, Instant computedAt) {
        jdbc.sql("DELETE FROM module_daily_stats WHERE stat_date = :day")
                .param("day", day)
                .update();

        return jdbc.sql("""
                INSERT INTO module_daily_stats (
                    stat_date, project_id, module_id,
                    open_overdue, open_wip, open_not_started, computed_at)
                SELECT :day, t.project_id, t.module_id,
                    -- One CASE, not three SUMs: the arms are evaluated in
                    -- order, so a ticket lands in exactly one segment and the
                    -- three add up to the module's open total. Overdue first,
                    -- deliberately — see the class note.
                    COALESCE(SUM(CASE
                        WHEN t.planned_close_date IS NOT NULL
                             AND t.planned_close_date < :day THEN 1
                        ELSE 0 END), 0) AS overdue_at_eod,
                    COALESCE(SUM(CASE
                        WHEN t.planned_close_date IS NOT NULL
                             AND t.planned_close_date < :day THEN 0
                        WHEN s.category = 'IN_PROGRESS' THEN 1
                        ELSE 0 END), 0) AS wip_at_eod,
                    COALESCE(SUM(CASE
                        WHEN t.planned_close_date IS NOT NULL
                             AND t.planned_close_date < :day THEN 0
                        WHEN s.category = 'TODO' THEN 1
                        ELSE 0 END), 0) AS not_started_at_eod,
                    :computedAt
                FROM tickets t
                JOIN statuses s ON s.code = t.status
                WHERE t.module_id IS NOT NULL
                  -- Outstanding work only. DONE covers CLOSED and
                  -- RESOLVED-not-CLOSED alike; see the class note.
                  AND s.category IN ('TODO', 'IN_PROGRESS')
                  -- Still open when the day ended, by the same predicate
                  -- refreshTicketStats and refreshClientStats use.
                  AND t.date_reported < :dayEnd
                  AND (t.actual_close_date IS NULL OR t.actual_close_date >= :dayEnd)
                GROUP BY t.project_id, t.module_id
                -- A pair with no open work earns no row. Without this every
                -- module would get a row of zeroes on every day for ever,
                -- growing the table by projects times modules times days
                -- regardless of activity.
                HAVING overdue_at_eod > 0 OR wip_at_eod > 0 OR not_started_at_eod > 0
                """)
                .param("day", day)
                .param("dayEnd", day.plusDays(1).atStartOfDay())
                .param("computedAt", computedAt)
                .update();
    }

    /**
     * One measured gap: a ticket left some stage at {@code leftAt} and arrived
     * at {@code stageCode} at {@code arrivedAt}.
     *
     * <p>The stage left is not carried, because nothing reads it — the gap is
     * charged to the receiving queue, and keeping a field the accumulation
     * ignores would invite somebody to group by it and change the meaning of
     * the column without changing its name.
     */
    private record Handoff(long projectId, String stageCode, Instant leftAt, Instant arrivedAt) {

        Key key() {
            return new Key(projectId, stageCode);
        }

        record Key(long projectId, String stageCode) {
        }
    }
}
