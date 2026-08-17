package com.edunext.edutrack.worker.stats;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
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
 * A-057 for the SLA columns and by A-062 for the resource-keyed aging and due
 * counts, so the precedent is established in the code and nowhere in the
 * ownership map.
 * <b>Flagged rather than edited quietly</b>, per CLAUDE.md. The ownership row
 * wants amending to carve out {@code worker/stats/} for Stream A the way the
 * hash verifier already is, or these edits keep arriving unannounced.
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

    DailyStatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
}
