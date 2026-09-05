package com.edunext.edutrack.worker.onboarding.digest;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * B-114 · which steps have stopped moving, and whose manager should hear about
 * it.
 *
 * <h2>What "stuck" means, and why it is two things</h2>
 *
 * <p>A step is stuck when it has been going nowhere for longer than the
 * threshold, and there are two ways for that to be true:
 *
 * <ol>
 *   <li><strong>Overdue.</strong> {@code due_at} passed more than the threshold
 *       ago and the step is still open.</li>
 *   <li><strong>Parked.</strong> BLOCKED or WAITING_ON_CLIENT since longer than
 *       the threshold.</li>
 * </ol>
 *
 * <p>The second case is the one that would be missed, and missing it would make
 * the digest blind to the most common real stall. §5.7 stops the clock while a
 * step waits on the client, so a step parked for three weeks <em>never becomes
 * overdue</em>. That is correct for a TAT and useless to a manager, who is
 * exactly the person who should be chasing a client that has gone quiet. So the
 * two are found separately and every row says which it is, because what the
 * reader does next depends on whose court the ball is in.
 *
 * <h2>Where "stalled since" comes from</h2>
 *
 * <p>A-105's {@code ob_step_clock_events} is the record of when a step was
 * paused, and the latest PAUSED <em>after</em> the latest RESUMED is the pause
 * it is in now. {@code ob_journey_steps.updated_at} would be wrong for this: it
 * moves when anybody edits anything on the row, so a step blocked for a month
 * looks freshly blocked the moment somebody corrects its description.
 *
 * <p>The fallback to {@code started_at} then {@code created_at} is deliberate
 * rather than defensive padding. A step whose lifecycle wrote no clock event is
 * still a step that has not moved, and the honest answer is "since we first saw
 * it" instead of dropping it out of the digest.
 *
 * <h2>Who the manager is</h2>
 *
 * <p>{@code users.reporting_manager_id} of the step's owner — the only manager
 * relation the schema has today. §5.11's escalation matrix is where "who hears
 * about this client's delays" becomes configuration rather than the org chart,
 * and that is <strong>B-113</strong>. When it lands, this join is what it
 * replaces; nothing else in the digest changes.
 *
 * <p>Steps whose owner has no manager, or no owner at all, therefore reach
 * nobody. That is a real gap and it is reported rather than hidden — see
 * {@link #unattributedStuckSteps}, which the scheduler logs on every run so the
 * gap is a number somebody can see rather than silence.
 *
 * <h2>Counted and filtered in SQL</h2>
 *
 * <p>The same call {@code DigestRepository} (D-038) made, for the same reason:
 * this runs once a day off a clock with nobody waiting, and the alternative is
 * loading every open step in the organisation into the worker to group it.
 */
@Repository
public class ObDigestRepository {

    /**
     * The open steps of journeys that are actually running.
     *
     * <p>A journey that is archived, complete, still behind the prerequisite
     * gate (§5.3) or still held by another journey (§5.5) is not stuck — it is
     * waiting by design, and a digest that says otherwise trains its reader to
     * ignore it. Both holds are checked because they are independent: a journey
     * can be past the gate and still held.
     *
     * <p>The client filter is {@code ONBOARDING} rather than "not LIVE".
     * ON_HOLD and DROPPED are deliberate stops with a recorded reason, and
     * mailing a manager every morning about a client somebody put on hold is
     * how a digest becomes noise.
     *
     * <p>A CTE, because {@code stalledSince} is a subquery and MySQL cannot see
     * a SELECT alias from WHERE. Repeating the expression in the predicate is
     * the alternative, and it is the kind of duplication that gets corrected in
     * one of its two places.
     */
    private static final String STUCK = """
            WITH stuck AS (
              SELECT mgr.id                    AS managerId,
                     mgr.email                 AS managerEmail,
                     mgr.full_name             AS managerName,
                     c.id                      AS obClientId,
                     c.name                    AS clientName,
                     p.name                    AS productName,
                     s.id                      AS stepId,
                     s.name                    AS stepName,
                     s.status                  AS status,
                     s.due_at                  AS dueAt,
                     own.full_name             AS ownerName,
                     COALESCE(
                       (SELECT e.occurred_at
                          FROM ob_step_clock_events e
                         WHERE e.step_id = s.id
                           AND e.event_type = 'PAUSED'
                           AND e.occurred_at > COALESCE(
                                 (SELECT MAX(r.occurred_at)
                                    FROM ob_step_clock_events r
                                   WHERE r.step_id = s.id
                                     AND r.event_type = 'RESUMED'),
                                 TIMESTAMP('1970-01-01'))
                         ORDER BY e.occurred_at DESC, e.id DESC
                         LIMIT 1),
                       s.started_at,
                       s.created_at)           AS stalledSince
                FROM ob_journey_steps s
                JOIN ob_journeys      j   ON j.id   = s.journey_id
                JOIN ob_clients       c   ON c.id   = j.ob_client_id
                JOIN ob_products      p   ON p.id   = j.product_id
                JOIN users            own ON own.id = s.owner_user_id
                JOIN users            mgr ON mgr.id = own.reporting_manager_id
               WHERE j.archived_at   IS NULL
                 AND j.completed_at  IS NULL
                 AND j.gate_status    = 'OPEN'
                 AND (j.held_by_journey_id IS NULL OR j.released_at IS NOT NULL)
                 AND s.status IN ('IN_PROGRESS', 'BLOCKED', 'WAITING_ON_CLIENT')
                 AND c.overall_status = 'ONBOARDING'
                 AND mgr.is_active    = 1
                 AND mgr.email IS NOT NULL
            )
            SELECT *
              FROM stuck
             WHERE (dueAt IS NOT NULL AND dueAt < :cutoff)
                OR (status IN ('BLOCKED', 'WAITING_ON_CLIENT') AND stalledSince < :cutoff)
             ORDER BY managerId, stalledSince, stepId
            """;

    /**
     * The same sweep, for the steps the manager join threw away.
     *
     * <p>Counted rather than listed: it is a health figure for the log, not
     * something anybody is mailed. The join conditions are inverted exactly —
     * no owner, no manager, or a manager who cannot be mailed.
     */
    private static final String UNATTRIBUTED = """
            SELECT COUNT(*)
              FROM ob_journey_steps s
              JOIN ob_journeys j   ON j.id   = s.journey_id
              JOIN ob_clients  c   ON c.id   = j.ob_client_id
         LEFT JOIN users       own ON own.id = s.owner_user_id
         LEFT JOIN users       mgr ON mgr.id = own.reporting_manager_id
             WHERE j.archived_at  IS NULL
               AND j.completed_at IS NULL
               AND j.gate_status   = 'OPEN'
               AND (j.held_by_journey_id IS NULL OR j.released_at IS NOT NULL)
               AND s.status IN ('IN_PROGRESS', 'BLOCKED', 'WAITING_ON_CLIENT')
               AND c.overall_status = 'ONBOARDING'
               AND (mgr.id IS NULL OR mgr.is_active = 0 OR mgr.email IS NULL)
               AND ((s.due_at IS NOT NULL AND s.due_at < :cutoff)
                 OR (s.status IN ('BLOCKED', 'WAITING_ON_CLIENT')
                     AND COALESCE(s.started_at, s.created_at) < :cutoff))
            """;

    /**
     * Which of today's digests have already been queued.
     *
     * <p>"One mail a day" cannot rest on A-107's unique index alone.
     * {@code queued_dedupe_key} is NULL once a row leaves PENDING or SENDING —
     * which is the point of it, and what lets a second TAT reminder be queued
     * later — so by nine o'clock the morning's digest no longer blocks
     * anything. A restart, a redeploy or a hand-run of
     * {@link ObManagerDigestScheduler} would queue every manager a second copy.
     *
     * <p>So the day is part of the key, and this is the read that enforces it:
     * over every status rather than the queued ones, and one query for the
     * whole run rather than one per manager.
     */
    private static final String QUEUED_TODAY = """
            SELECT dedupe_key
              FROM ob_notification_outbox
             WHERE event_key = :eventKey
               AND dedupe_key IN (:keys)
            """;

    private final JdbcClient jdbc;

    ObDigestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every stuck step whose owner reports to somebody, oldest stall first
     * within each manager.
     *
     * @param cutoff the instant a step must have been stuck since — the
     *               threshold in working days, already resolved to a moment by
     *               {@link ObDigestCalendar.Snapshot#workingDaysBefore}
     */
    public List<StuckStep> stuckSteps(Instant cutoff) {
        return jdbc.sql(STUCK)
                .param("cutoff", Timestamp.from(cutoff))
                .query(MAPPER)
                .list();
    }

    /**
     * By hand, because {@code DATETIME(6)} does not map itself onto an
     * {@link Instant}.
     *
     * <p>{@code SimplePropertyRowMapper} — what {@code query(StuckStep.class)}
     * would use — reads the column as a {@link java.time.LocalDateTime} and
     * then has no converter to the record's {@code Instant}. The fix is not to
     * declare the record in local time: storage is UTC everywhere (CLAUDE.md),
     * these values are compared against a cutoff computed in the calendar's
     * zone, and a {@code LocalDateTime} carrying UTC wall time is exactly the
     * silent-conversion bug {@code DATETIME} was chosen over {@code TIMESTAMP}
     * to avoid. {@code getTimestamp().toInstant()} is what
     * {@code ObOutboxRepository} does, one package over, for the same reason.
     */
    private static final RowMapper<StuckStep> MAPPER = (ResultSet rs, int rowNum) -> new StuckStep(
            rs.getLong("managerId"),
            rs.getString("managerEmail"),
            rs.getString("managerName"),
            rs.getLong("obClientId"),
            rs.getString("clientName"),
            rs.getString("productName"),
            rs.getLong("stepId"),
            rs.getString("stepName"),
            rs.getString("status"),
            instant(rs, "dueAt"),
            rs.getString("ownerName"),
            instant(rs, "stalledSince"));

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** How many stuck steps reach nobody, because no manager resolved. */
    public int unattributedStuckSteps(Instant cutoff) {
        return jdbc.sql(UNATTRIBUTED)
                .param("cutoff", Timestamp.from(cutoff))
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    /** Of {@code keys}, the ones a digest row already exists for — in any status. */
    public Set<String> alreadyQueued(String eventKey, Set<String> keys) {
        if (keys.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql(QUEUED_TODAY)
                .param("eventKey", eventKey)
                .param("keys", keys)
                .query(String.class)
                .list());
    }

    /**
     * One stuck step, with everything its digest line needs.
     *
     * @param status       IN_PROGRESS, BLOCKED or WAITING_ON_CLIENT — the
     *                     difference the reader acts on
     * @param dueAt        null on a step that never had a TAT computed; such a
     *                     step reaches the digest only through a long park
     * @param stalledSince when it last moved. See the class note on where this
     *                     comes from and why it is not {@code updated_at}
     */
    public record StuckStep(long managerId, String managerEmail, String managerName,
                            long obClientId, String clientName, String productName,
                            long stepId, String stepName, String status,
                            Instant dueAt, String ownerName, Instant stalledSince) {
    }
}
