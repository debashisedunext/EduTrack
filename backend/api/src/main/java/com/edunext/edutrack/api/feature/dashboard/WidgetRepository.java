package com.edunext.edutrack.api.feature.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A-056 · the reads behind widgets 7–12. Never {@code tickets}.
 *
 * <p>Separate from {@link DashboardRepository} rather than added to it: that
 * class answers the KPI cards and is already 230 lines, and six more queries
 * would have doubled it into the kind of file where nobody notices a
 * {@code SUM} over a stock column. Same rule governs both — CLAUDE.md's <b>never
 * a live {@code COUNT(*)} for dashboards</b> — and every statement below reads
 * {@code daily_ticket_stats} or {@code resource_daily_stats}. The only other
 * tables touched are masters ({@code task_types}, {@code users},
 * {@code project_members}) and only ever to turn an id into a name or a scope
 * into a set of ids, which is bounded by the size of the organisation rather
 * than by the number of tickets.
 *
 * <h2>🔴 The resource-keyed widgets cannot be scoped exactly, and here is why</h2>
 *
 * <p>Widgets 9 and 10 read {@code resource_daily_stats}, which A-050 keyed
 * {@code (stat_date, user_id)} with <b>no project column</b>. A PM's scope is a
 * set of projects, and there is no way to intersect it with a table that does
 * not record one. So those two widgets are scoped by <em>membership</em>
 * instead: a PM sees the resources who belong to their projects, via
 * {@code project_members}.
 *
 * <p>That is not the same statement and the difference is worth being explicit
 * about. A developer who works on the PM's project <em>and</em> two others
 * appears in the chart with their <b>whole</b> output, not the part that
 * belongs to the asking PM. The figure is real and the person is legitimately
 * visible; the attribution is coarser than the project widgets beside it.
 *
 * <p>Fixing it properly means a {@code project_id} on {@code resource_daily_stats},
 * which changes its grain from one row per person per day to one per person per
 * project per day, and that is A-050's table and a decision with its own
 * consequences for A-062 and the reports in M6. <b>Recorded here rather than
 * decided here</b> — but recorded, so that a PM who notices a resource's numbers
 * exceeding their project's finds the reason written down instead of filing it
 * as a bug.
 */
@Repository
class WidgetRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    WidgetRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ── widget 7 · task type donut ───────────────────────────────────────────

    /**
     * The task-type breakdown as at the latest summarised day in range.
     *
     * <p>Stock, not flow — it counts what is <em>open</em> by type, matching
     * {@code open_total} and the level columns rather than counting creations,
     * exactly as the {@code type_counts} migration specifies. So it reads the
     * latest day rather than summing the window: summing a fortnight of "how
     * many are open" gives a number fourteen times too large that still looks
     * entirely plausible on a donut, because a donut shows proportions and the
     * proportions would be right.
     *
     * <p>The per-project JSON objects are merged in Java rather than with
     * MySQL's {@code JSON_MERGE_PATCH}, which <em>replaces</em> a duplicate key
     * rather than adding it — two projects each with 4 open bugs would merge to
     * 4. {@code JSON_TABLE} could do it in SQL, at the cost of a statement
     * nobody can read against a row count bounded by the number of projects.
     *
     * @return task_type_id to open count, empty when the day has no rows or
     *         every row's {@code type_counts} is still NULL.
     */
    Map<Long, Long> openByTaskType(LocalDate from, LocalDate to, List<Long> projectIds, Long projectFilter) {
        List<String> documents = jdbc.sql("""
                        SELECT type_counts
                          FROM daily_ticket_stats
                         WHERE stat_date = (
                                   SELECT MAX(stat_date) FROM daily_ticket_stats
                                    WHERE stat_date BETWEEN :from AND :to
                                      AND (:unscoped = 1 OR project_id IN (:projectIds))
                                      AND (:projectFilter IS NULL OR project_id = :projectFilter))
                           AND (:unscoped = 1 OR project_id IN (:projectIds))
                           AND (:projectFilter IS NULL OR project_id = :projectFilter)
                           AND type_counts IS NOT NULL
                        """)
                .param("from", from)
                .param("to", to)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .param("projectFilter", projectFilter)
                .query(String.class)
                .list();

        Map<Long, Long> merged = new LinkedHashMap<>();
        for (String document : documents) {
            Map<String, Integer> counts = parseCounts(document);
            counts.forEach((typeId, count) ->
                    merged.merge(Long.valueOf(typeId), count.longValue(), Long::sum));
        }
        return merged;
    }

    private Map<String, Integer> parseCounts(String document) {
        try {
            return json.readValue(document, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            // The worker wrote this with JSON_OBJECTAGG into a JSON column, so
            // MySQL has already validated it. Unreadable here means the column
            // was written by something else, which is a defect to surface and
            // not a donut to silently draw short one slice.
            throw new IllegalStateException(
                    "daily_ticket_stats.type_counts holds a value that is not a JSON object: " + document,
                    malformed);
        }
    }

    /**
     * Ids to names, so renaming a task type does not rewrite the history keyed
     * by its id.
     *
     * <p>Deactivated types included deliberately. A ticket raised against a type
     * an Admin has since retired is still open and still has to be named, and
     * filtering on {@code is_active} would drop its slice from the donut while
     * leaving it inside {@code open_total} — a chart that no longer adds up to
     * the card above it.
     */
    Map<Long, String> taskTypeNames() {
        Map<Long, String> names = new LinkedHashMap<>();
        jdbc.sql("SELECT id, name FROM task_types ORDER BY seq, name")
                .query((rs, n) -> Map.entry(rs.getLong("id"), rs.getString("name")))
                .list()
                .forEach(entry -> names.put(entry.getKey(), entry.getValue()));
        return names;
    }

    // ── widget 8 · daily created / closed / reopened ─────────────────────────

    /** One row per summarised day. Flow only — every column here sums meaningfully over a range. */
    record DailyFlow(LocalDate day, long created, long closed, long reopened) {
    }

    List<DailyFlow> dailyFlow(LocalDate from, LocalDate to, List<Long> projectIds, Long projectFilter) {
        return jdbc.sql("""
                        SELECT stat_date,
                               SUM(created)  AS created,
                               SUM(closed)   AS closed,
                               SUM(reopened) AS reopened
                          FROM daily_ticket_stats
                         WHERE stat_date BETWEEN :from AND :to
                           AND (:unscoped = 1 OR project_id IN (:projectIds))
                           AND (:projectFilter IS NULL OR project_id = :projectFilter)
                         GROUP BY stat_date
                         ORDER BY stat_date
                        """)
                .param("from", from)
                .param("to", to)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .param("projectFilter", projectFilter)
                .query((rs, n) -> new DailyFlow(
                        rs.getObject("stat_date", LocalDate.class),
                        rs.getLong("created"), rs.getLong("closed"), rs.getLong("reopened")))
                .list();
    }

    // ── widgets 9 & 10 · the resource-keyed pair ─────────────────────────────

    /**
     * Widget 9 — tickets closed per resource per week.
     *
     * <p>Weeks rather than days because §S-05 specifies "tickets/week", and a
     * daily line per resource over thirty days is thirty points of noise on
     * fifteen overlapping lines.
     *
     * <p>Grouped by {@code YEARWEEK(…, 3)} — ISO-8601 mode, weeks starting
     * Monday, week 1 being the one with at least four days in the new year.
     * Mode 3 rather than the default 0 because the default starts weeks on
     * Sunday, which would split every working week across two points and make
     * every resource look half as productive twice as often. The label is
     * rebuilt as the Monday's date, since {@code 202634} is not something to
     * put on an axis.
     */
    record ResourceWeek(long userId, String resourceName, LocalDate weekStart, long closed) {
    }

    List<ResourceWeek> velocityByWeek(LocalDate from, LocalDate to, List<Long> projectIds, Long userFilter) {
        return jdbc.sql("""
                        SELECT r.user_id,
                               u.full_name,
                               MIN(r.stat_date - INTERVAL (WEEKDAY(r.stat_date)) DAY) AS week_start,
                               SUM(r.closed) AS closed
                          FROM resource_daily_stats r
                          JOIN users u ON u.id = r.user_id
                         WHERE r.stat_date BETWEEN :from AND :to
                           AND (:unscoped = 1 OR r.user_id IN (
                                   SELECT pm.user_id FROM project_members pm
                                    WHERE pm.project_id IN (:projectIds)
                                      AND pm.is_active = 1))
                           AND (:userFilter IS NULL OR r.user_id = :userFilter)
                         GROUP BY r.user_id, u.full_name, YEARWEEK(r.stat_date, 3)
                         HAVING closed > 0
                         ORDER BY u.full_name, week_start
                        """)
                .param("from", from)
                .param("to", to)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .param("userFilter", userFilter)
                .query((rs, n) -> new ResourceWeek(
                        rs.getLong("user_id"),
                        rs.getString("full_name"),
                        rs.getObject("week_start", LocalDate.class),
                        rs.getLong("closed")))
                .list();
    }

    /**
     * Widget 10 — the three-segment load bar, as at the latest summarised day.
     *
     * <p>{@code waiting} is derived here rather than stored: open, minus the
     * two states that have their own column. The migration guarantees the
     * subtraction cannot go negative by defining {@code assigned_in_progress}
     * disjointly from {@code assigned_delayed} — but it is clamped anyway,
     * because a schema guarantee that silently produces a negative bar segment
     * if it is ever wrong is a guarantee worth not betting the rendering on.
     */
    record ResourceLoad(long userId, String resourceName, long waiting, long inProgress, long delayed) {
    }

    List<ResourceLoad> resourceLoad(LocalDate from, LocalDate to, List<Long> projectIds, Long userFilter) {
        return jdbc.sql("""
                        SELECT r.user_id,
                               u.full_name,
                               r.assigned_open,
                               r.assigned_in_progress,
                               r.assigned_delayed
                          FROM resource_daily_stats r
                          JOIN users u ON u.id = r.user_id
                         WHERE r.stat_date = (
                                   SELECT MAX(stat_date) FROM resource_daily_stats
                                    WHERE stat_date BETWEEN :from AND :to)
                           AND r.assigned_open > 0
                           AND (:unscoped = 1 OR r.user_id IN (
                                   SELECT pm.user_id FROM project_members pm
                                    WHERE pm.project_id IN (:projectIds)
                                      AND pm.is_active = 1))
                           AND (:userFilter IS NULL OR r.user_id = :userFilter)
                         ORDER BY r.assigned_open DESC, u.full_name
                        """)
                .param("from", from)
                .param("to", to)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .param("userFilter", userFilter)
                .query((rs, n) -> {
                    long open = rs.getLong("assigned_open");
                    long inProgress = rs.getLong("assigned_in_progress");
                    long delayed = rs.getLong("assigned_delayed");
                    return new ResourceLoad(
                            rs.getLong("user_id"),
                            rs.getString("full_name"),
                            Math.max(0, open - inProgress - delayed),
                            inProgress,
                            delayed);
                })
                .list();
    }

    // ── widgets 11 & 12 · the two stock breakdowns ───────────────────────────

    /**
     * Widgets 11 and 12 — priority split and aging buckets, as at the latest
     * summarised day.
     *
     * <p>One query for both because they are the same read of the same row and
     * differ only in which eight columns are projected. Stock again, so the
     * latest day rather than a {@code SUM} over the window — the same mistake
     * as the donut and equally invisible on a bar chart, where four bars seven
     * times too tall look exactly like four bars.
     *
     * <p><b>The aging edges are the schema's, not the blueprint's.</b> §S-05
     * draws 0–2 / 3–5 / 6–10 / >10 days; A-050 stored 0–2 / 3–7 / 8–30 / 31+
     * and its migration header explains why the edges are fixed in the column
     * rather than computed at read time. The labels below follow the columns,
     * because a chart whose axis disagrees with the number it is drawing is
     * worse than one whose buckets disagree with a document. Listed in PLAN.md
     * §4 territory and flagged in the backlog note.
     */
    record StockBreakdown(
            long openLow, long openMedium, long openHigh, long openCritical,
            long aging02, long aging37, long aging830, long aging31Plus) {
    }

    Optional<StockBreakdown> stockBreakdown(LocalDate from, LocalDate to,
                                            List<Long> projectIds, Long projectFilter) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(open_low), 0)      AS open_low,
                               COALESCE(SUM(open_medium), 0)   AS open_medium,
                               COALESCE(SUM(open_high), 0)     AS open_high,
                               COALESCE(SUM(open_critical), 0) AS open_critical,
                               COALESCE(SUM(aging_0_2), 0)     AS aging_0_2,
                               COALESCE(SUM(aging_3_7), 0)     AS aging_3_7,
                               COALESCE(SUM(aging_8_30), 0)    AS aging_8_30,
                               COALESCE(SUM(aging_31_plus), 0) AS aging_31_plus
                          FROM daily_ticket_stats
                         WHERE stat_date = (
                                   SELECT MAX(stat_date) FROM daily_ticket_stats
                                    WHERE stat_date BETWEEN :from AND :to
                                      AND (:unscoped = 1 OR project_id IN (:projectIds))
                                      AND (:projectFilter IS NULL OR project_id = :projectFilter))
                           AND (:unscoped = 1 OR project_id IN (:projectIds))
                           AND (:projectFilter IS NULL OR project_id = :projectFilter)
                        """)
                .param("from", from)
                .param("to", to)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .param("projectFilter", projectFilter)
                .query((rs, n) -> new StockBreakdown(
                        rs.getLong("open_low"), rs.getLong("open_medium"),
                        rs.getLong("open_high"), rs.getLong("open_critical"),
                        rs.getLong("aging_0_2"), rs.getLong("aging_3_7"),
                        rs.getLong("aging_8_30"), rs.getLong("aging_31_plus")))
                .optional();
    }

    /**
     * An empty {@code IN ()} list is a MySQL syntax error, and the guard against
     * reaching it is the {@code :unscoped} flag beside every use. The sentinel
     * exists because the parameter is still <em>bound</em> even when the flag
     * short-circuits the branch — {@code DashboardRepository} does the same, in
     * the same shape, and the two are deliberately identical: an id no row can
     * hold, so a mistake in the flag denies rather than admits.
     */
    private static List<Long> scopeOrSentinel(List<Long> projectIds) {
        return projectIds.isEmpty() ? List.of(-1L) : projectIds;
    }
}
