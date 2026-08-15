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
                    aging_0_2, aging_3_7, aging_8_30, aging_31_plus, computed_at)
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
                    computed_at = VALUES(computed_at)
                """)
                .param("day", day)
                .param("dayStart", day.atStartOfDay())
                .param("dayEnd", day.plusDays(1).atStartOfDay())
                .param("computedAt", computedAt)
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
                    assigned_open, assigned_critical, assigned_delayed, computed_at)
                SELECT :day, u.id,
                    COALESCE(c.closed, 0),
                    COALESCE(e.hours, 0),
                    COALESCE(a.open_count, 0),
                    COALESCE(a.critical_count, 0),
                    COALESCE(a.delayed_count, 0),
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
                           SUM(planned_close_date < :dayEnd) AS delayed_count
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
                .param("computedAt", computedAt)
                .update();
    }
}
