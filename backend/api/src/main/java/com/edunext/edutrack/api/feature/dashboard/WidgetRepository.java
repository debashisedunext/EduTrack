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
     * A-062 · widget 12 for one person, and the day it was measured for.
     *
     * <p>The same four buckets as {@link #stockBreakdown}, from the columns
     * V20260817_1130 added — deliberately with the project table's edges, so a
     * Developer's chart and their PM's are two views of one definition rather
     * than two definitions. The migration header carries that argument.
     *
     * <p>{@code measuredOn} is returned alongside the counts because the aging
     * drill-downs are built by subtracting a bucket's edges from the day the
     * bar was measured. Deriving that from {@code to} instead would open a list
     * anchored on a day the figures are not from — over a weekend, off by two —
     * and the shifted list would look entirely reasonable.
     *
     * <p>Stock, so the latest day rather than a sum: adding a fortnight of "how
     * many were 0–2 days old" counts each ticket once per day it was in the
     * bucket and yields four bars several times too tall, in the right
     * proportions.
     */
    record ResourceAging(LocalDate measuredOn, long aging02, long aging37,
                         long aging830, long aging31Plus) {
    }

    Optional<ResourceAging> resourceAging(LocalDate from, LocalDate to, long userId) {
        return jdbc.sql("""
                        SELECT stat_date,
                               assigned_aging_0_2     AS aging_0_2,
                               assigned_aging_3_7     AS aging_3_7,
                               assigned_aging_8_30    AS aging_8_30,
                               assigned_aging_31_plus AS aging_31_plus
                          FROM resource_daily_stats
                         WHERE user_id = :userId
                           AND stat_date = (
                                   SELECT MAX(stat_date) FROM resource_daily_stats
                                    WHERE stat_date BETWEEN :from AND :to AND user_id = :userId)
                        """)
                .param("from", from).param("to", to).param("userId", userId)
                .query((rs, n) -> new ResourceAging(
                        rs.getObject("stat_date", LocalDate.class),
                        rs.getLong("aging_0_2"), rs.getLong("aging_3_7"),
                        rs.getLong("aging_8_30"), rs.getLong("aging_31_plus")))
                .optional();
    }

    // ── widget 13 · the calendar heatmap's per-day activity ──────────────────

    /**
     * One point per day for a delivery role's heatmap.
     *
     * <p>{@code resource_daily_stats.closed} — what this person finished that
     * day. Deliberately a <em>different measure</em> from the project heatmap's
     * "created", and the service labels the series accordingly: intake is not a
     * fact about an assignee, and showing a Developer their project's daily
     * intake on a chart headed with their own name would be the same
     * mis-attribution {@code DashboardRepository.resourceFlow} already refuses.
     */
    record ResourceDay(LocalDate day, long closed) {
    }

    List<ResourceDay> resourceDailyClosed(LocalDate from, LocalDate to, long userId) {
        return jdbc.sql("""
                        SELECT stat_date, closed
                          FROM resource_daily_stats
                         WHERE stat_date BETWEEN :from AND :to AND user_id = :userId
                         ORDER BY stat_date
                        """)
                .param("from", from).param("to", to).param("userId", userId)
                .query((rs, n) -> new ResourceDay(
                        rs.getObject("stat_date", LocalDate.class), rs.getLong("closed")))
                .list();
    }

    // ── widget 14 · SLA compliance ───────────────────────────────────────────

    /**
     * Widget 14 — of the work finished in the window, how much landed on time.
     *
     * <p><b>Flow, summed across the window</b>, which is the one thing about
     * this widget that is easy to get wrong. The instinct is to read compliance
     * off {@code open_delayed}, which is stock and answers "what is late right
     * now" — a gauge fed from that would tick <em>upwards</em> every time
     * somebody closed an overdue ticket, reporting late delivery as an
     * improvement. The migration header carries the full argument.
     *
     * <p>Days A-051 has not computed hold NULL and {@code SUM} skips them, so
     * the gauge answers over the days it actually has rather than diluting the
     * ratio with zeroes. {@code asOf} is already on screen to say how current
     * that is.
     *
     * @param closed tickets closed in the window that carried a
     *               {@code planned_close_date}. Tickets without one made no
     *               commitment and are in neither half.
     * @param met    of those, closed on or before it.
     */
    record SlaCompliance(long closed, long met) {
    }

    Optional<SlaCompliance> slaCompliance(LocalDate from, LocalDate to,
                                          List<Long> projectIds, Long projectFilter) {
        return jdbc.sql("""
                        SELECT SUM(sla_closed) AS sla_closed,
                               SUM(sla_met)    AS sla_met
                          FROM daily_ticket_stats
                         WHERE stat_date BETWEEN :from AND :to
                           AND (:unscoped = 1 OR project_id IN (:projectIds))
                           AND (:projectFilter IS NULL OR project_id = :projectFilter)
                        """)
                .param("from", from)
                .param("to", to)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .param("projectFilter", projectFilter)
                .query((rs, n) -> {
                    long closed = rs.getLong("sla_closed");
                    // A window of entirely uncomputed days sums to SQL NULL,
                    // which getLong reports as 0 — indistinguishable from "no
                    // SLA work closed". wasNull separates them, and the service
                    // needs that distinction to choose between "100%" and
                    // "nothing to measure yet".
                    if (rs.wasNull()) {
                        return null;
                    }
                    long met = rs.getLong("sla_met");
                    // Clamped rather than trusted. sla_met <= sla_closed holds
                    // by construction, but a rendered percentage is not the
                    // place to discover that a recompute disagreed.
                    return new SlaCompliance(closed, Math.min(met, closed));
                })
                .optional()
                .filter(java.util.Objects::nonNull);
    }

    // ── widget 15 · project treemap ──────────────────────────────────────────

    /**
     * Widget 15 — open tickets per project, as at the latest summarised day.
     *
     * <p>Stock, so the latest day rather than a sum. On a treemap the error
     * would be perfectly invisible: every rectangle scales by the same factor,
     * so a fortnight summed looks exactly like a day — identical proportions,
     * identical layout, and only the tooltip figure wrong.
     *
     * <p>Projects with nothing open are omitted rather than drawn as
     * zero-area rectangles carrying a label with nowhere to sit.
     */
    record ProjectShare(long projectId, String projectName, long openTotal) {
    }

    List<ProjectShare> projectDistribution(LocalDate from, LocalDate to,
                                           List<Long> projectIds, Long projectFilter) {
        return jdbc.sql("""
                        SELECT s.project_id, p.name, s.open_total
                          FROM daily_ticket_stats s
                          JOIN projects p ON p.id = s.project_id
                         WHERE s.stat_date = (
                                   SELECT MAX(stat_date) FROM daily_ticket_stats
                                    WHERE stat_date BETWEEN :from AND :to
                                      AND (:unscoped = 1 OR project_id IN (:projectIds))
                                      AND (:projectFilter IS NULL OR project_id = :projectFilter))
                           AND s.open_total > 0
                           AND (:unscoped = 1 OR s.project_id IN (:projectIds))
                           AND (:projectFilter IS NULL OR s.project_id = :projectFilter)
                         ORDER BY s.open_total DESC, p.name
                        """)
                .param("from", from)
                .param("to", to)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .param("projectFilter", projectFilter)
                .query((rs, n) -> new ProjectShare(
                        rs.getLong("project_id"), rs.getString("name"), rs.getLong("open_total")))
                .list();
    }

    // ── widget 20 · client-wise volume ───────────────────────────────────────

    /**
     * A-059 · tickets raised per client over the window.
     *
     * <p><b>Flow, so summed</b> — and this is the one place in this class where
     * summing over days is the correct operation rather than the bug the other
     * queries guard against. "Volume" is intake: blueprint §7.8 lists it
     * separately from "open versus closed", and a count of creations aggregates
     * over a date range by construction. Widget 15's treemap next door reads a
     * single day for exactly the opposite reason, and the two sitting side by
     * side is why both carry the argument.
     *
     * <p><b>Scoped by project, which is why the table carries one.</b> A client
     * spans projects, so the sum is over the projects this caller can see. Two
     * callers with different scopes get different bars for one client, and each
     * is the honest answer to "this client's volume, within the work you can
     * open". The migration header carries the disclosure argument for why the
     * table could not be keyed by client alone.
     *
     * <p>Clients with nothing raised in the window are absent rather than zero,
     * the same way {@link #projectDistribution} omits empty projects: a bar of
     * length zero is a label with no bar, and a horizontal chart of them is a
     * list of clients who did not appear.
     */
    record ClientVolume(long clientId, String clientName, long created) {
    }

    List<ClientVolume> clientVolume(LocalDate from, LocalDate to,
                                    List<Long> projectIds, Long projectFilter) {
        return jdbc.sql("""
                        SELECT s.client_id, c.name, SUM(s.created) AS raised
                          FROM client_daily_stats s
                          JOIN clients c ON c.id = s.client_id
                         WHERE s.stat_date BETWEEN :from AND :to
                           AND (:unscoped = 1 OR s.project_id IN (:projectIds))
                           AND (:projectFilter IS NULL OR s.project_id = :projectFilter)
                         GROUP BY s.client_id, c.name
                        HAVING raised > 0
                         ORDER BY raised DESC, c.name
                        """)
                .param("from", from)
                .param("to", to)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .param("projectFilter", projectFilter)
                .query((rs, n) -> new ClientVolume(
                        rs.getLong("client_id"), rs.getString("name"), rs.getLong("raised")))
                .list();
    }

    /**
     * Dashboard Rework Dev 2, PR 14 · one day of module_daily_stats.
     *
     * <p><b>One date, never a range.</b> These are stock columns: a ticket open
     * on Monday and still open on Friday is in both days' rows, so summing a
     * range would count it five times. {@code clientVolume} above sums freely
     * because {@code created} is flow. The widget therefore reads the latest
     * computed day rather than the caller's window — the same reason the
     * treemap and the stock breakdowns do.
     */
    List<ModuleOpen> moduleOpen(LocalDate day, List<Long> projectIds, Long projectFilter) {
        return jdbc.sql("""
                        SELECT s.module_id, m.name,
                               SUM(s.open_overdue)     AS overdue,
                               SUM(s.open_wip)         AS wip,
                               SUM(s.open_not_started) AS not_started
                          FROM module_daily_stats s
                          JOIN product_modules m ON m.id = s.module_id
                         WHERE s.stat_date = :day
                           AND (:unscoped = 1 OR s.project_id IN (:projectIds))
                           AND (:projectFilter IS NULL OR s.project_id = :projectFilter)
                         GROUP BY s.module_id, m.name
                        HAVING overdue > 0 OR wip > 0 OR not_started > 0
                         ORDER BY (overdue + wip + not_started) DESC, m.name
                        """)
                .param("day", day)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .param("projectFilter", projectFilter)
                .query((rs, n) -> new ModuleOpen(
                        rs.getLong("module_id"), rs.getString("name"),
                        rs.getLong("overdue"), rs.getLong("wip"), rs.getLong("not_started")))
                .list();
    }

    /** The most recent day module_daily_stats holds, or null before the worker has run. */
    LocalDate latestModuleStatDate(List<Long> projectIds) {
        return jdbc.sql("""
                        SELECT MAX(stat_date) FROM module_daily_stats
                         WHERE (:unscoped = 1 OR project_id IN (:projectIds))
                        """)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .query(LocalDate.class)
                .optional()
                .orElse(null);
    }

    record ModuleOpen(long moduleId, String moduleName, long overdue, long wip, long notStarted) {
    }

    // ── A-058 · widgets 16–19, the four the ribbon unlocks ───────────────────

    /**
     * A-058 · has the ribbon recorded anything at all for the projects this
     * caller can see?
     *
     * <h2>Why four widgets ask this before drawing</h2>
     *
     * <p>Widgets 16–19 all derive from {@code ticket_stage_transitions}. On a
     * database where the ribbon is not yet in use, every one of them computes
     * cleanly to nothing — an empty funnel, zero tickets in rework, no stage
     * durations, no handoffs — and every one of those renders as a <em>claim</em>:
     * that no work is queued anywhere, and in widget 17's case that no ticket has
     * ever been sent back. "Nothing was measured" and "the measurement is zero"
     * are the same picture and opposite facts.
     *
     * <p>A-068 was caught by exactly this and its answer is the precedent
     * followed here: first-time-right showed 100% beside an empty bounce table,
     * computed from a counter nothing had ever incremented, and it is now
     * withheld unless a backward move was observed. <b>Nothing measured renders
     * as a sentence, never as a zero.</b> A-057's SLA gauge says the same.
     *
     * <p>Asked of {@code stage_daily_stats} rather than of the transitions
     * themselves, so the rule against reading that table from a dashboard holds
     * here too. A row exists there for every (project, stage) with any ribbon
     * activity on any day, so its absence across a caller's whole scope is
     * exactly the statement being tested.
     *
     * <p><b>Deliberately unbounded by the date window.</b> A quiet fortnight is
     * not an unpopulated ribbon, and gating on the window would withhold widget
     * 17 from a team whose tickets are bouncing today because none of them
     * happened to move during it. {@code LIMIT 1} on
     * {@code ix_stage_stats_project} makes the breadth free.
     */
    boolean ribbonHasData(List<Long> projectIds, Long projectFilter) {
        return jdbc.sql("""
                        SELECT 1
                          FROM stage_daily_stats
                         WHERE (:unscoped = 1 OR project_id IN (:projectIds))
                           AND (:projectFilter IS NULL OR project_id = :projectFilter)
                         LIMIT 1
                        """)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .param("projectFilter", projectFilter)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * A-058 · widget 16 — open tickets per stage, from
     * {@code daily_ticket_stats.wip_by_stage}.
     *
     * <p>The column A-050 declared and left NULL against this task by name, now
     * filled by {@code DailyStatsRepository.refreshWipByStage} from the
     * transitions rather than from {@code tickets.current_stage} — see that
     * method for why the obvious source would have rewritten history.
     *
     * <p><b>The latest day in the window, never the sum of it.</b> WIP is stock:
     * "how many tickets sit in each stage" is true at an instant, and a
     * fortnight of it summed is a funnel fourteen times too tall whose
     * <em>proportions</em> are right — which is exactly what makes it survive
     * review. {@code openByTaskType} above reads the same way for the same
     * reason and this is the third widget to state it.
     *
     * <p>Merged in Java rather than with {@code JSON_MERGE_PATCH}, which
     * replaces a duplicate key rather than adding it: two projects each with 4
     * tickets in QA would merge to 4.
     *
     * @return stage code to open count, empty when the day has no rows or every
     *         row's {@code wip_by_stage} is still NULL
     */
    Map<String, Long> openByStage(LocalDate from, LocalDate to, List<Long> projectIds, Long projectFilter) {
        List<String> documents = jdbc.sql("""
                        SELECT wip_by_stage
                          FROM daily_ticket_stats
                         WHERE stat_date = (
                                   SELECT MAX(stat_date) FROM daily_ticket_stats
                                    WHERE stat_date BETWEEN :from AND :to
                                      AND (:unscoped = 1 OR project_id IN (:projectIds))
                                      AND (:projectFilter IS NULL OR project_id = :projectFilter))
                           AND (:unscoped = 1 OR project_id IN (:projectIds))
                           AND (:projectFilter IS NULL OR project_id = :projectFilter)
                           AND wip_by_stage IS NOT NULL
                        """)
                .param("from", from)
                .param("to", to)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .param("projectFilter", projectFilter)
                .query(String.class)
                .list();

        Map<String, Long> merged = new LinkedHashMap<>();
        for (String document : documents) {
            parseStageCounts(document).forEach((stage, count) ->
                    merged.merge(stage, count.longValue(), Long::sum));
        }
        return merged;
    }

    private Map<String, Integer> parseStageCounts(String document) {
        try {
            return json.readValue(document, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            // Written by JSON_OBJECTAGG into a JSON column, so MySQL has already
            // validated it. Unreadable here means something else wrote the
            // column, which is a defect to surface rather than a funnel to draw
            // one band short.
            throw new IllegalStateException(
                    "daily_ticket_stats.wip_by_stage holds a value that is not a JSON object: " + document,
                    malformed);
        }
    }

    /**
     * A-058 · the ribbon's stage vocabulary — code to display name, in ribbon
     * order.
     *
     * <p>Distinct on the code, because {@code workflow_stages} is keyed
     * {@code (template_id, stage_code)} and every template declares its own row
     * for {@code QA}. The dashboard aggregates across projects and therefore
     * across templates, so it needs one label per code; {@code MIN(seq)} orders
     * them by the earliest position any template gives them, which is the only
     * ordering that exists once templates disagree.
     *
     * <p>A code with no surviving definition is simply absent here, and the
     * widgets fall back to the code itself. That is deliberate:
     * {@code V20260818_2140} deprecates stage codes, and a bar vanishing from a
     * funnel because its master row was retired would silently understate the
     * work in front of it.
     */
    Map<String, String> stageNames() {
        Map<String, String> names = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT stage_code, MIN(display_name) AS display_name
                          FROM workflow_stages
                      GROUP BY stage_code
                      ORDER BY MIN(seq), stage_code
                        """)
                .query((rs, n) -> Map.entry(rs.getString("stage_code"), rs.getString("display_name")))
                .list()
                .forEach(entry -> names.put(entry.getKey(), entry.getValue()));
        return names;
    }

    /**
     * A-058 · widget 17 — open tickets in rework, and the ping-pong subset.
     *
     * <p><b>Stock, so the latest day and not the sum.</b> A ticket stuck at
     * iteration 4 for a fortnight would otherwise count fourteen times, and the
     * resulting card would read as a crisis that is really one ticket.
     *
     * <p>Summed <em>across projects</em> on that day, which is sound for the
     * reason {@code client_daily_stats}' header gives: a ticket belongs to
     * exactly one project, so space adds and time does not.
     */
    Optional<ReworkCounts> reworkCounts(LocalDate from, LocalDate to,
                                        List<Long> projectIds, Long projectFilter) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(rework_open), 0)   AS rework,
                               COALESCE(SUM(pingpong_open), 0) AS pingpong,
                               COALESCE(SUM(open_total), 0)    AS open_total
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
                .query((rs, n) -> new ReworkCounts(
                        rs.getLong("rework"), rs.getLong("pingpong"), rs.getLong("open_total")))
                .optional()
                // A window with no summarised day at all aggregates to one row
                // of zeroes rather than to none, and that row is indistinguishable
                // from a genuinely quiet organisation. Filtered to empty so the
                // service can say "not summarised yet" instead of "nothing is in
                // rework" — the distinction this whole widget family turns on.
                .filter(counts -> counts.openTotal() > 0 || counts.rework() > 0);
    }

    /**
     * @param openTotal the same day's open count, carried so the widget can say
     *                  "12 of 80" rather than "12". A rework figure with no
     *                  denominator is unreadable — twelve is a disaster in a
     *                  team of twenty tickets and a rounding error in two
     *                  thousand — and taking it from this row rather than from a
     *                  second query guarantees both halves describe one day.
     */
    record ReworkCounts(long rework, long pingpong, long openTotal) {
    }

    /**
     * A-058 · widget 18 — elapsed and active minutes per stage across the whole
     * window.
     *
     * <p><b>Flow, so summed</b>, and the sums are what make the average right.
     * Averaging the daily averages would weight a day with one sealed visit
     * equally with a day with fifty; summing the minutes and the visits
     * separately and dividing once at the end does not. That is the reason
     * {@code stage_daily_stats} stores totals and no average.
     *
     * <p>{@code visits} counts <em>exits</em>, not entries. A stage entered on
     * Monday and left on Thursday contributes its duration to Thursday, and
     * dividing by arrivals would divide one day's durations by a different
     * day's tickets.
     *
     * <p>Stages whose visits all remain unsealed are absent rather than zero:
     * the worker writes no elapsed minutes for a visit still in progress, so a
     * stage that only ever receives and never releases has nothing to average —
     * which widget 16's funnel is the place to see, and drawing it here as a
     * zero-height bar would say the opposite.
     */
    List<StageDuration> stageDurations(LocalDate from, LocalDate to,
                                       List<Long> projectIds, Long projectFilter) {
        return jdbc.sql("""
                        SELECT stage_code,
                               SUM(exited)       AS visits,
                               SUM(elapsed_mins) AS elapsed_mins,
                               SUM(active_mins)  AS active_mins
                          FROM stage_daily_stats
                         WHERE stat_date BETWEEN :from AND :to
                           AND (:unscoped = 1 OR project_id IN (:projectIds))
                           AND (:projectFilter IS NULL OR project_id = :projectFilter)
                      GROUP BY stage_code
                        HAVING visits > 0
                        """)
                .param("from", from)
                .param("to", to)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .param("projectFilter", projectFilter)
                .query((rs, n) -> new StageDuration(
                        rs.getString("stage_code"), rs.getLong("visits"),
                        rs.getLong("elapsed_mins"), rs.getLong("active_mins")))
                .list();
    }

    record StageDuration(String stageCode, long visits, long elapsedMins, long activeMins) {
    }

    /**
     * A-058 · widget 19 — handoff latency per day, for the trend line.
     *
     * <p>Grouped by day rather than by stage because §7.9 draws this one as a
     * trend: the question is whether queue waste is growing, which a bar per
     * stage cannot answer and a line over dates can. The per-stage cut of the
     * same figures is A-067's stage-cycle-time report, one screen over.
     *
     * <p>Minutes and count both summed, divided once by the caller. A day where
     * nothing was handed over is absent rather than zero — a zero on this line
     * reads as "handoffs were instant that day", which is a claim about a day on
     * which nothing happened.
     */
    List<HandoffDay> handoffLatency(LocalDate from, LocalDate to,
                                    List<Long> projectIds, Long projectFilter) {
        return jdbc.sql("""
                        SELECT stat_date,
                               SUM(handoff_count) AS handoffs,
                               SUM(handoff_mins)  AS minutes
                          FROM stage_daily_stats
                         WHERE stat_date BETWEEN :from AND :to
                           AND (:unscoped = 1 OR project_id IN (:projectIds))
                           AND (:projectFilter IS NULL OR project_id = :projectFilter)
                      GROUP BY stat_date
                        HAVING handoffs > 0
                      ORDER BY stat_date
                        """)
                .param("from", from)
                .param("to", to)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .param("projectFilter", projectFilter)
                .query((rs, n) -> new HandoffDay(
                        // getObject rather than getDate().toLocalDate(): A-067
                        // found that conversion going through the JVM default
                        // zone in four places, so a date stored in UTC and read
                        // on an IST machine came back a day early. Every existing
                        // test asserted counts and never dates, so it was
                        // invisible until a report printed one.
                        rs.getObject("stat_date", LocalDate.class),
                        rs.getLong("handoffs"), rs.getLong("minutes")))
                .list();
    }

    record HandoffDay(LocalDate day, long handoffs, long minutes) {
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
