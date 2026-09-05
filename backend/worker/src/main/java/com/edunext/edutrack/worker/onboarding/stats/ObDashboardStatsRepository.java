package com.edunext.edutrack.worker.onboarding.stats;

import com.edunext.edutrack.domain.masters.WorkingHoursService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * B-120 · recomputes one day of {@code ob_dashboard_summary} and
 * {@code ob_implementor_daily_stats} from the journey tables.
 *
 * <p>A-108 created two tables nothing fills, and CLAUDE.md forbids a live
 * {@code COUNT(*)} behind a dashboard, so OB-02 has no other source: until this
 * runs, every card on it reads zero. That is the failure mode worth stating
 * first, because an unrefreshed table and a genuinely quiet week look identical
 * on screen — {@code computed_at} is what tells them apart, and every statement
 * below writes it.
 *
 * <h2>🔴 Stock is today-only. Flow is windowed. This is the whole design.</h2>
 *
 * <p>A-051's ticketing equivalent recomputes a trailing week of everything,
 * because every ticketing figure derives from a timestamp the row carries and
 * so is reproducible for any past date. <b>Half of these columns are not.</b>
 * A-108's header says so directly: RAG, gate status and step status are
 * <em>current</em> values with no history behind them, so "how many ERP
 * journeys were amber on 12 August" is unrecoverable once that day passes.
 *
 * <p>Recomputing a past day's stock would therefore not repair it — it would
 * overwrite it with <em>today's</em> answer, silently, and the trend chart above
 * it would flatten into a straight line at today's value while looking exactly
 * as it always has. This is A-051's own "not faithful to history" warning about
 * {@code assigned_to}, except that here it would apply to the majority of the
 * table rather than to one column. So:
 *
 * <ul>
 *   <li>{@link #refreshSummaryStock} and {@link #refreshImplementorStock} are
 *       only ever called for the <em>current</em> day. They delete and rewrite
 *       it, so a product or an implementor that stops earning a row loses one.</li>
 *   <li>{@link #refreshSummaryFlow}, {@link #refreshImplementorFlow} and
 *       {@link #refreshBlockedHours} derive from {@code started_at},
 *       {@code completed_at}, {@code finished_at} and the append-only history —
 *       all immutable — so they are recomputed across a trailing window and an
 *       outage costs nothing permanent.</li>
 * </ul>
 *
 * <p>The window is additionally clamped to {@link #genesis()}, the oldest day
 * already in the table. Without that clamp the first pass would write flow rows
 * for the week before the table existed, each carrying stock zeroes — and a
 * zero is a claim ("no journeys were open") where the absence of a row is merely
 * silence. A-108 chose silence: "both start empty and fill forward from the day
 * they land, and a chart blank for its first days is correct rather than
 * broken."
 *
 * <h2>The arithmetic contracts are the reason for the CASE expressions</h2>
 *
 * <p>A-108 states two of them as obligations on this class rather than as hints,
 * and neither can be enforced by the schema:
 *
 * <ul>
 *   <li>{@code rag_green + rag_amber + rag_red = journeys_open_running}, and
 *       {@code locked + held + running + completed = journeys_total}.</li>
 *   <li>{@code on_track + not_started + delayed + at_risk + blocked_waiting +
 *       ahead_of_schedule = clients_open}.</li>
 * </ul>
 *
 * <p>Independent {@code SUM}s would break both the moment a journey is overdue
 * <em>and</em> blocked, every product's figures would overstate in proportion to
 * how badly it is running, and each number would stay individually plausible. So
 * each is one {@code CASE} per journey and one per (implementor, client) pair,
 * arms evaluated in order, worst wins.
 *
 * <h2>What counts as overdue, and the trap in it</h2>
 *
 * <p>{@link #OVERDUE} excludes {@code WAITING_ON_CLIENT}. Plan §5.7 stops that
 * step's clock, so its stored {@code due_at} is a promise that has been
 * suspended rather than missed, and painting it red would charge the
 * organisation for a delay it attributed to the client — which is precisely the
 * attribution {@code ob_step_clock_events} exists to defend in a §14 dispute.
 *
 * <p>{@code BLOCKED} is <em>not</em> excluded, and that asymmetry is deliberate:
 * A-105's header is explicit that an internal block keeps burning TAT. A step
 * blocked on our own infrastructure is late when its due date passes, and it is
 * exactly the row the Delayed Projects grid exists to find.
 *
 * <h2>Set-based, one statement per pass</h2>
 *
 * <p>Every product in one {@code GROUP BY}, every implementor in one more.
 * {@link #refreshBlockedHours} is the single exception and states its own
 * reason.
 */
@Repository
public class ObDashboardStatsRepository {

    /**
     * A step that is still somebody's to do. Shared by every statement below so
     * that "how many are open" cannot come to mean two things on one screen —
     * the drift A-051 avoided by making its own open predicate a single
     * definition across three tables.
     */
    private static final String OPEN =
            "s.status IN ('PENDING', 'IN_PROGRESS', 'BLOCKED', 'WAITING_ON_CLIENT')";

    /** See the class note. {@code WAITING_ON_CLIENT} is out; {@code BLOCKED} is in. */
    private static final String OVERDUE = """
            (s.status IN ('PENDING', 'IN_PROGRESS', 'BLOCKED')
             AND s.due_at IS NOT NULL AND s.due_at < :now)""";

    /**
     * Past {@code amberShare} of the way from start to due, and not yet overdue.
     * Measured against the step's own window rather than a fixed number of hours
     * before the deadline, because {@code tat_days} varies from one step to
     * twenty and "warn me a day early" means something different on each.
     *
     * <p>A step that has not started has no elapsed share and so is never amber.
     * {@code PENDING} work that is already late is caught by {@link #OVERDUE}
     * instead, and work merely waiting to begin is not a health problem until
     * its own clock is running.
     */
    private static final String AMBER = """
            (s.status IN ('PENDING', 'IN_PROGRESS', 'BLOCKED')
             AND s.due_at IS NOT NULL AND s.started_at IS NOT NULL
             AND s.due_at >= :now
             AND TIMESTAMPADD(MICROSECOND,
                     FLOOR(TIMESTAMPDIFF(MICROSECOND, s.started_at, s.due_at) * :amberShare),
                     s.started_at) <= :now)""";

    /**
     * Finished on an earlier <em>calendar day</em> than promised, in the
     * organisation's zone.
     *
     * <p>Day-grained rather than instant-grained because the promise itself is:
     * {@code tat_days} is a count of days, and OB-05 draws one of three markers
     * — 🙌 early, 👍 on time, 👎 delayed — against it. An instant-grained rule
     * would call a step finished ninety seconds inside its deadline "early" and
     * hand the performance score a distinction nobody made.
     *
     * <p>The offset is added before {@code DATE()} rather than calling
     * {@code CONVERT_TZ}, for the reason {@link ObStatsDay} gives: the named
     * timezone tables are not guaranteed to be loaded, and {@code CONVERT_TZ}
     * answers NULL rather than failing when they are not.
     */
    private static final String EARLY = """
            (DATE(s.finished_at + INTERVAL :zoneOffsetSeconds SECOND)
             < DATE(s.due_at + INTERVAL :zoneOffsetSeconds SECOND))""";

    /** Past the promised instant. Instant-grained, unlike {@link #EARLY}: a deadline is a deadline. */
    private static final String LATE = "(s.finished_at > s.due_at)";

    private final JdbcClient jdbc;

    /**
     * The one figure here that SQL cannot compute, for the reason A-058 gives on
     * its own use of this service: CLAUDE.md routes all duration maths through
     * the working calendar, and {@code blocked_hours} is a duration.
     * {@code TIMESTAMPDIFF} would report a step blocked on Friday evening and
     * unblocked on Monday morning as 62 hours of lost time, on the one grid
     * whose purpose is finding lost time.
     */
    private final WorkingHoursService workingHours;

    ObDashboardStatsRepository(JdbcClient jdbc, WorkingHoursService workingHours) {
        this.jdbc = jdbc;
        this.workingHours = workingHours;
    }

    /**
     * The oldest day the summary already holds, or empty on a table that has
     * never been refreshed. The flow window is clamped to it; see the class note
     * on why a zero row is worse than no row.
     */
    public Optional<LocalDate> genesis() {
        return jdbc.sql("SELECT MIN(stat_date) FROM ob_dashboard_summary")
                .query(LocalDate.class)
                .optional();
    }

    // ------------------------------------------------------------------
    // ob_dashboard_summary
    // ------------------------------------------------------------------

    /**
     * The current day's stock columns: the journey buckets, the three RAG counts
     * and the OB-02 cards.
     *
     * <h2>Deleted and rewritten, not upserted</h2>
     *
     * <p>{@code refreshModuleStats}' argument, for the same shape of mutable
     * input: a product earns its row by having a live journey and can stop
     * earning one, because a journey is archived rather than deleted. An upsert
     * cannot retract what it wrote, so the last product a client dropped would
     * keep yesterday's counts on the board indefinitely.
     *
     * <p>Three statements rather than one. The cards are aggregates over
     * {@code ob_journey_steps} and over {@code ob_clients}, at two grains neither
     * of which is the journey; folding them into the first statement as
     * correlated subqueries is the shape that deadlocked A-056, since it reads
     * the steps table from inside an {@code INSERT … SELECT} already holding
     * shared locks on its parent. They run in one transaction, so a dashboard
     * reading mid-pass never sees this pass's journey counts beside the last
     * pass's cards.
     *
     * <p><b>Public deliberately.</b> Spring's transaction attribute source
     * ignores {@code @Transactional} on a non-public method and says nothing when
     * it does, which would autocommit the DELETE on its own and leave every
     * dashboard reading in that window with an empty board.
     *
     * @return rows written by the journey pass
     */
    @Transactional
    public int refreshSummaryStock(ObStatsDay day, Instant now, Instant computedAt, BigDecimal amberShare) {
        jdbc.sql("DELETE FROM ob_dashboard_summary WHERE stat_date = :day")
                .param("day", day.date())
                .update();

        int rows = jdbc.sql("""
                INSERT INTO ob_dashboard_summary (
                    stat_date, product_id,
                    journeys_total, journeys_locked, journeys_held,
                    journeys_open_running, journeys_completed,
                    rag_green, rag_amber, rag_red,
                    computed_at)
                SELECT :day, x.product_id,
                    COUNT(*),
                    COALESCE(SUM(x.bucket = 'LOCKED'), 0),
                    COALESCE(SUM(x.bucket = 'HELD'), 0),
                    COALESCE(SUM(x.bucket = 'RUNNING'), 0),
                    COALESCE(SUM(x.bucket = 'COMPLETED'), 0),
                    -- Conditioned on RUNNING, not merely on the colour: a locked
                    -- journey has no RAG at all (A-108, and the contract's
                    -- nullable `rag`), and folding it into green would report a
                    -- client who has not started as on track.
                    COALESCE(SUM(x.bucket = 'RUNNING' AND x.rag = 'GREEN'), 0),
                    COALESCE(SUM(x.bucket = 'RUNNING' AND x.rag = 'AMBER'), 0),
                    COALESCE(SUM(x.bucket = 'RUNNING' AND x.rag = 'RED'), 0),
                    :computedAt
                FROM (
                    SELECT j.product_id,
                        -- One CASE per journey, arms in order, so the four
                        -- buckets partition journeys_total exactly once.
                        -- Completed first: a finished journey is not described
                        -- by whatever its gate or its hold last said.
                        CASE
                            WHEN j.completed_at IS NOT NULL THEN 'COMPLETED'
                            WHEN j.gate_status = 'LOCKED' THEN 'LOCKED'
                            WHEN j.held_by_journey_id IS NOT NULL
                                 AND j.released_at IS NULL THEN 'HELD'
                            ELSE 'RUNNING'
                        END AS bucket,
                        -- Worst-wins over the journey's open steps (plan §5.9).
                        -- Evaluated for every journey and discarded above for
                        -- the ones with no colour, which keeps the colour rule
                        -- in one place rather than repeating it per bucket.
                        CASE
                            WHEN EXISTS (
                                SELECT 1 FROM ob_journey_steps s
                                 WHERE s.journey_id = j.id AND %s) THEN 'RED'
                            WHEN EXISTS (
                                SELECT 1 FROM ob_journey_steps s
                                 WHERE s.journey_id = j.id AND %s) THEN 'AMBER'
                            ELSE 'GREEN'
                        END AS rag
                    FROM ob_journeys j
                    -- Archiving is this module's soft delete. An archived
                    -- journey keeps its history and leaves the board.
                    WHERE j.archived_at IS NULL
                ) x
                GROUP BY x.product_id
                """.formatted(OVERDUE, AMBER))
                .param("day", day.date())
                .param("now", now)
                .param("amberShare", amberShare)
                .param("computedAt", computedAt)
                .update();

        jdbc.sql("""
                UPDATE ob_dashboard_summary d
                  JOIN (
                    SELECT j.product_id,
                        -- §9's cards count "all client tasks" — services and
                        -- prerequisites alike — so nothing here filters on kind.
                        COALESCE(SUM(s.due_at >= :dayStart AND s.due_at < :dayEnd), 0) AS due_today,
                        COALESCE(SUM(s.due_at >= :weekStart AND s.due_at < :weekEnd), 0) AS due_week,
                        COALESCE(SUM(%s), 0) AS overdue
                    FROM ob_journey_steps s
                    JOIN ob_journeys j ON j.id = s.journey_id AND j.archived_at IS NULL
                    WHERE %s
                    GROUP BY j.product_id
                  ) x ON x.product_id = d.product_id
                   SET d.steps_due_today     = x.due_today,
                       d.steps_due_this_week = x.due_week,
                       d.steps_overdue       = x.overdue
                 WHERE d.stat_date = :day
                """.formatted(OVERDUE, OPEN))
                .param("day", day.date())
                .param("dayStart", day.start())
                .param("dayEnd", day.end())
                .param("weekStart", day.weekStart())
                .param("weekEnd", day.weekEnd())
                .param("now", now)
                .update();

        jdbc.sql("""
                UPDATE ob_dashboard_summary d
                  JOIN (
                    SELECT j.product_id,
                        -- COUNT(DISTINCT client), not COUNT(item): A-108's own
                        -- note. One client late on three services is one Overdue
                        -- Client, and the two figures differ exactly when the
                        -- card matters most.
                        COUNT(DISTINCT CASE WHEN EXISTS (
                            SELECT 1 FROM ob_journey_steps s
                             WHERE s.journey_id = j.id AND %s)
                          THEN j.ob_client_id END) AS overdue_clients,
                        COUNT(DISTINCT CASE WHEN c.overall_status = 'LIVE'
                          THEN j.ob_client_id END) AS live_clients,
                        COUNT(DISTINCT CASE WHEN c.overall_status = 'ONBOARDING'
                          THEN j.ob_client_id END) AS onboarding_clients,
                        -- Open escalations, again per client: A-128 allows one
                        -- open escalation per service, so a client unhappy with
                        -- three would otherwise treble its own card.
                        COUNT(DISTINCT CASE WHEN EXISTS (
                            SELECT 1 FROM ob_client_escalations e
                             WHERE e.journey_id = j.id AND e.resolved_at IS NULL)
                          THEN j.ob_client_id END) AS escalated_clients
                    FROM ob_journeys j
                    JOIN ob_clients c ON c.id = j.ob_client_id
                    WHERE j.archived_at IS NULL
                    GROUP BY j.product_id
                  ) x ON x.product_id = d.product_id
                   SET d.clients_overdue    = x.overdue_clients,
                       d.clients_live       = x.live_clients,
                       d.clients_onboarding = x.onboarding_clients,
                       d.clients_escalated  = x.escalated_clients
                 WHERE d.stat_date = :day
                """.formatted(OVERDUE))
                .param("day", day.date())
                .param("now", now)
                .update();

        return rows;
    }

    /**
     * One day's flow columns, upserted.
     *
     * <p>Every figure derives from an immutable timestamp, so this is safe to run
     * for a past day and is what recovers an outage. It upserts rather than
     * deleting: the row it lands on may already carry that day's stock, and that
     * stock cannot be recomputed.
     *
     * <p>{@code journeys_went_live} reads {@code completed_at} rather than
     * {@code ob_clients.live_at}. The grain here is the product, and a client
     * goes live once while each of their services completes on its own day;
     * client-level go-live is B-118's flag and a different question.
     *
     * @return rows written
     */
    @Transactional
    public int refreshSummaryFlow(ObStatsDay day, Instant computedAt) {
        return jdbc.sql("""
                INSERT INTO ob_dashboard_summary (
                    stat_date, product_id,
                    journeys_started, journeys_went_live, steps_completed, computed_at)
                SELECT :day, a.product_id, a.started, a.went_live, COALESCE(b.done, 0), :computedAt
                FROM (
                    SELECT j.product_id,
                        COALESCE(SUM(j.started_at   >= :dayStart AND j.started_at   < :dayEnd), 0) AS started,
                        COALESCE(SUM(j.completed_at >= :dayStart AND j.completed_at < :dayEnd), 0) AS went_live
                    FROM ob_journeys j
                    WHERE j.archived_at IS NULL
                    GROUP BY j.product_id
                ) a
                LEFT JOIN (
                    SELECT j.product_id, COUNT(*) AS done
                    FROM ob_journey_steps s
                    JOIN ob_journeys j ON j.id = s.journey_id AND j.archived_at IS NULL
                    WHERE s.status = 'DONE'
                      AND s.finished_at >= :dayStart AND s.finished_at < :dayEnd
                    GROUP BY j.product_id
                ) b ON b.product_id = a.product_id
                ON DUPLICATE KEY UPDATE
                    journeys_started   = VALUES(journeys_started),
                    journeys_went_live = VALUES(journeys_went_live),
                    steps_completed    = VALUES(steps_completed),
                    computed_at        = VALUES(computed_at)
                """)
                .param("day", day.date())
                .param("dayStart", day.start())
                .param("dayEnd", day.end())
                .param("computedAt", computedAt)
                .update();
    }

    // ------------------------------------------------------------------
    // ob_implementor_daily_stats
    // ------------------------------------------------------------------

    /**
     * The current day's workload grid: one row per implementor, six disjoint
     * buckets over the clients they carry.
     *
     * <h2>A row is written for an implementor with zero clients</h2>
     *
     * <p>§9 asks for it explicitly and A-108 flags it as the requirement most
     * likely to be lost, because the natural implementation groups by owner over
     * open steps and so produces nothing at all for somebody who has just
     * finished everything — the grid then shows a fully-delivered implementor as
     * absent rather than as clear, which is the opposite reading. The population
     * is therefore a {@code LEFT JOIN} target computed first, and the counts hang
     * off it.
     *
     * <p>That population is the union of two answers, because neither alone is
     * right. A live {@code OB_STEP_OWNER} grant is who the organisation says does
     * this work — it is what puts the bench on the grid. Current ownership of a
     * step is who is actually carrying something — it stops a person whose grant
     * was revoked while they still held live work from vanishing off the board
     * along with their clients.
     *
     * <h2>Worst wins, and the order is a judgement worth stating</h2>
     *
     * <p>{@code delayed} outranks {@code blocked_waiting}: a step that is both
     * blocked and past its date is late, and reporting it as merely blocked would
     * let a delay hide behind its own excuse — which is the number the grid
     * exists to surface. Everything below those two describes work that is not
     * yet in trouble, so the order there follows §9's own reading: at risk, then
     * not started, then ahead, then on track.
     *
     * @return rows written
     */
    @Transactional
    public int refreshImplementorStock(ObStatsDay day, Instant now, Instant computedAt, BigDecimal amberShare) {
        jdbc.sql("DELETE FROM ob_implementor_daily_stats WHERE stat_date = :day")
                .param("day", day.date())
                .update();

        return jdbc.sql("""
                INSERT INTO ob_implementor_daily_stats (
                    stat_date, user_id,
                    clients_open, on_track, not_started, `delayed`,
                    at_risk, blocked_waiting, ahead_of_schedule,
                    computed_at)
                SELECT :day, pop.user_id,
                    COALESCE(w.clients_open, 0),
                    COALESCE(w.on_track, 0),
                    COALESCE(w.not_started, 0),
                    COALESCE(w.delayed_clients, 0),
                    COALESCE(w.at_risk, 0),
                    COALESCE(w.blocked_waiting, 0),
                    COALESCE(w.ahead, 0),
                    :computedAt
                FROM (
                    SELECT uma.user_id
                      FROM user_module_access uma
                     WHERE uma.module = 'ONBOARDING'
                       AND uma.module_role = 'OB_STEP_OWNER'
                       AND uma.revoked_at IS NULL
                    UNION
                    SELECT DISTINCT s.owner_user_id
                      FROM ob_journey_steps s
                      JOIN ob_journeys j ON j.id = s.journey_id AND j.archived_at IS NULL
                     WHERE s.owner_user_id IS NOT NULL
                ) pop
                LEFT JOIN (
                    SELECT c.owner_user_id AS user_id,
                        COUNT(*) AS clients_open,
                        COALESCE(SUM(c.bucket = 'ON_TRACK'), 0)        AS on_track,
                        COALESCE(SUM(c.bucket = 'NOT_STARTED'), 0)     AS not_started,
                        COALESCE(SUM(c.bucket = 'DELAYED'), 0)         AS delayed_clients,
                        COALESCE(SUM(c.bucket = 'AT_RISK'), 0)         AS at_risk,
                        COALESCE(SUM(c.bucket = 'BLOCKED_WAITING'), 0) AS blocked_waiting,
                        COALESCE(SUM(c.bucket = 'AHEAD'), 0)           AS ahead
                    FROM (
                        SELECT s.owner_user_id, j.ob_client_id,
                            CASE
                                WHEN MAX(%s) = 1 THEN 'DELAYED'
                                WHEN MAX(s.status IN ('BLOCKED', 'WAITING_ON_CLIENT')) = 1
                                    THEN 'BLOCKED_WAITING'
                                WHEN MAX(%s) = 1 THEN 'AT_RISK'
                                -- Nothing of this implementor's has begun for
                                -- this client. MIN over a boolean is "all".
                                WHEN MIN(s.status = 'PENDING') = 1 THEN 'NOT_STARTED'
                                -- Evidence of early delivery and none of late.
                                -- Deliberately a claim about finished work
                                -- rather than about how the open steps are
                                -- pacing: a step inside its TAT is on track, and
                                -- calling it "ahead" for having started early
                                -- would make this column a restatement of
                                -- on_track under a nicer name.
                                WHEN EXISTS (
                                        SELECT 1 FROM ob_journey_steps e
                                          JOIN ob_journeys ej
                                            ON ej.id = e.journey_id AND ej.archived_at IS NULL
                                         WHERE e.owner_user_id = s.owner_user_id
                                           AND ej.ob_client_id = j.ob_client_id
                                           AND e.status = 'DONE'
                                           AND e.finished_at IS NOT NULL AND e.due_at IS NOT NULL
                                           AND DATE(e.finished_at + INTERVAL :zoneOffsetSeconds SECOND)
                                             < DATE(e.due_at + INTERVAL :zoneOffsetSeconds SECOND))
                                     AND NOT EXISTS (
                                        SELECT 1 FROM ob_journey_steps l
                                          JOIN ob_journeys lj
                                            ON lj.id = l.journey_id AND lj.archived_at IS NULL
                                         WHERE l.owner_user_id = s.owner_user_id
                                           AND lj.ob_client_id = j.ob_client_id
                                           AND l.status = 'DONE'
                                           AND l.finished_at IS NOT NULL AND l.due_at IS NOT NULL
                                           AND l.finished_at > l.due_at)
                                    THEN 'AHEAD'
                                ELSE 'ON_TRACK'
                            END AS bucket
                        FROM ob_journey_steps s
                        JOIN ob_journeys j ON j.id = s.journey_id AND j.archived_at IS NULL
                        WHERE s.owner_user_id IS NOT NULL AND %s
                        -- The grid counts clients, not steps. Two open steps for
                        -- one client are one entry in one bucket.
                        GROUP BY s.owner_user_id, j.ob_client_id
                    ) c
                    GROUP BY c.owner_user_id
                ) w ON w.user_id = pop.user_id
                """.formatted(OVERDUE, AMBER, OPEN))
                .param("day", day.date())
                .param("now", now)
                .param("amberShare", amberShare)
                .param("zoneOffsetSeconds", day.zoneOffsetSeconds())
                .param("computedAt", computedAt)
                .update();
    }

    /**
     * One day's completion counters — the performance score's inputs.
     *
     * <p>A-108 keeps the score itself out of the table so that retuning the
     * weighting re-scores history consistently instead of stratifying it. These
     * three are the raw material, and they are per-day flow like every other flow
     * column: the grid sums them over whatever range it is showing.
     *
     * <p>A step finished with no {@code due_at} is counted in none of the three
     * rather than defaulting to on time. There was no promise to measure it
     * against, and crediting it would let unscheduled work inflate a score.
     *
     * @return rows written
     */
    @Transactional
    public int refreshImplementorFlow(ObStatsDay day, Instant computedAt) {
        return jdbc.sql("""
                INSERT INTO ob_implementor_daily_stats (
                    stat_date, user_id,
                    completed_on_time, completed_early, completed_late, computed_at)
                SELECT :day, s.owner_user_id,
                    COALESCE(SUM(NOT %1$s AND NOT %2$s), 0),
                    COALESCE(SUM(%1$s), 0),
                    COALESCE(SUM(%2$s), 0),
                    :computedAt
                FROM ob_journey_steps s
                JOIN ob_journeys j ON j.id = s.journey_id AND j.archived_at IS NULL
                WHERE s.owner_user_id IS NOT NULL
                  AND s.status = 'DONE'
                  AND s.finished_at >= :dayStart AND s.finished_at < :dayEnd
                  AND s.due_at IS NOT NULL
                GROUP BY s.owner_user_id
                ON DUPLICATE KEY UPDATE
                    completed_on_time = VALUES(completed_on_time),
                    completed_early   = VALUES(completed_early),
                    completed_late    = VALUES(completed_late),
                    computed_at       = VALUES(computed_at)
                """.formatted(EARLY, LATE))
                .param("day", day.date())
                .param("dayStart", day.start())
                .param("dayEnd", day.end())
                .param("zoneOffsetSeconds", day.zoneOffsetSeconds())
                .param("computedAt", computedAt)
                .update();
    }

    /**
     * Working hours this day that each implementor's steps spent blocked or
     * waiting on a client.
     *
     * <h2>The one pass that is not a single statement, and why</h2>
     *
     * <p>CLAUDE.md: all duration maths use the working calendar. A block that
     * opens on Friday evening and closes on Monday morning is not 62 hours of
     * lost time, and reporting it as such on the grid that weights a person's
     * score against blocks would be a false statement about them. SQL cannot
     * consult the calendar, so the intervals are read out, clipped to this day,
     * and folded through {@link WorkingHoursService} — the same call A-058 made
     * for handoff latency, for the same reason.
     *
     * <h2>The source is {@code ob_step_history}, not the clock</h2>
     *
     * <p>{@code ob_step_clock_events} is the obvious candidate and answers only
     * half the question: A-105's header is explicit that an internal
     * {@code BLOCKED} does <em>not</em> pause the clock, so a blocked step writes
     * no clock event at all and half of this column would be structurally
     * invisible. The status transitions in {@code ob_step_history} carry both,
     * are append-only and hash-chained, and are the record a §14 dispute would be
     * argued from.
     *
     * <p><b>What the fold reads is the shape C-107 already writes</b> —
     * {@code field_name = 'status'} with the old and new codes, appended through
     * {@code ObStepJournal}. C-107 is the table's only writer today and it writes
     * only the skip transition, so on {@code develop} the block and wait rows
     * come from A-101's fixture corpus alone and this column under-reports
     * accordingly. The query is written against the record designed to hold them
     * rather than against a second source, so it becomes complete on the commit
     * that adds those two transitions instead of needing a second
     * implementation. <b>Raised for C-104/C-105 rather than worked around.</b>
     *
     * <p><b>An interval runs until the next status row, and the last one runs
     * until now.</b> A step still blocked at the end of the day is charged for
     * the part of the day it was blocked, not for nothing — which is what a
     * closed-intervals-only query would report, and it would understate exactly
     * the situations the grid is for.
     *
     * <p>Correction rows are excluded on both sides of the fold. A compensating
     * entry re-states a transition that already has a row, so counting it would
     * open a second interval at the same instant and double the hours.
     *
     * @return rows written
     */
    @Transactional
    public int refreshBlockedHours(ObStatsDay day, Instant now, Instant computedAt) {
        Instant windowEnd = now.isBefore(day.end()) ? now : day.end();
        if (!day.start().isBefore(windowEnd)) {
            return 0;
        }

        List<StatusInterval> intervals = jdbc.sql("""
                SELECT i.user_id, i.from_at, i.to_at FROM (
                    SELECT s.owner_user_id AS user_id,
                           h.created_at AS from_at,
                           (SELECT MIN(h2.created_at)
                              FROM ob_step_history h2
                             WHERE h2.step_id = h.step_id
                               AND h2.field_name = 'status'
                               AND h2.is_correction = 0
                               AND h2.created_at > h.created_at) AS to_at
                    FROM ob_step_history h
                    JOIN ob_journey_steps s ON s.id = h.step_id
                    JOIN ob_journeys j ON j.id = s.journey_id AND j.archived_at IS NULL
                    WHERE h.field_name = 'status'
                      AND h.is_correction = 0
                      AND h.new_value IN ('BLOCKED', 'WAITING_ON_CLIENT')
                      AND s.owner_user_id IS NOT NULL
                      AND h.created_at < :windowEnd
                ) i
                WHERE i.to_at IS NULL OR i.to_at > :dayStart
                """)
                .param("windowEnd", windowEnd)
                .param("dayStart", day.start())
                // An explicit mapper rather than SimplePropertyRowMapper, which
                // reads DATETIME(6) as a LocalDateTime and has no converter to
                // Instant — the query throws on the first row. Declaring the
                // record in local time would "fix" it by reintroducing the
                // session-timezone conversion DATETIME was chosen over TIMESTAMP
                // to avoid.
                .query((rs, rowNum) -> {
                    Timestamp to = rs.getTimestamp("to_at");
                    return new StatusInterval(
                            rs.getLong("user_id"),
                            rs.getTimestamp("from_at").toInstant(),
                            to == null ? null : to.toInstant());
                })
                .list();

        Map<Long, BigDecimal> hoursByUser = new LinkedHashMap<>();
        for (StatusInterval interval : intervals) {
            Instant from = interval.from().isAfter(day.start()) ? interval.from() : day.start();
            Instant to = interval.to() == null || interval.to().isAfter(windowEnd)
                    ? windowEnd
                    : interval.to();
            if (!from.isBefore(to)) {
                continue;
            }
            // The org calendar alone — no userId. The step owner's own approved
            // leave must not shorten a block: the client is still waiting while
            // the owner is away, and netting it off would make a person's absence
            // look like the block ending.
            hoursByUser.merge(interval.userId(), workingHours.workingHoursBetween(from, to), BigDecimal::add);
        }

        int rows = 0;
        for (Map.Entry<Long, BigDecimal> entry : hoursByUser.entrySet()) {
            rows += jdbc.sql("""
                    INSERT INTO ob_implementor_daily_stats (stat_date, user_id, blocked_hours, computed_at)
                    VALUES (:day, :userId, :hours, :computedAt)
                    ON DUPLICATE KEY UPDATE
                        blocked_hours = VALUES(blocked_hours),
                        computed_at   = VALUES(computed_at)
                    """)
                    .param("day", day.date())
                    .param("userId", entry.getKey())
                    .param("hours", entry.getValue().setScale(0, RoundingMode.HALF_UP).intValue())
                    .param("computedAt", computedAt)
                    .update();
        }
        return rows;
    }

    /**
     * A step in a blocked or waiting status from {@code from} until {@code to},
     * where a null {@code to} means it has not left that status yet.
     *
     * <p>Which of the two statuses it was is not carried, because nothing reads
     * it: A-108's column is "blocked or waiting" as one figure. Keeping the
     * distinction here would invite somebody to group by it and change what the
     * column means without changing its name.
     */
    private record StatusInterval(long userId, Instant from, Instant to) {
    }
}
