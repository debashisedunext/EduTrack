package com.edunext.edutrack.api.feature.onboarding.mytasks;

import com.edunext.edutrack.common.pagination.Cursor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The implementor's open tasks, across every project, in one query.
 *
 * <h2>Three ways a task is yours</h2>
 *
 * <p>Owner, backup owner, or <b>inherited</b> — a task nobody was pinned to, on
 * a project you are the implementor of. The third is not a convenience: it is
 * the rule {@code ObJourneyStepLifecycle} already authorises by, so a task this
 * query attributes to somebody is one the five transitions will accept from
 * them. A queue that listed work the server would then refuse would be worse
 * than no queue.
 *
 * <p>The backup owner counts for the same reason
 * {@code OnboardingScopeResolver.hasStepOwnedBy} counts them: the backup exists
 * to cover the task when the owner cannot, and a queue that hid those would hide
 * exactly the work a stand-in has been asked to pick up.
 *
 * <h2>Newest first</h2>
 *
 * <p>Ordered by {@code created_at} descending: the task that arrived most
 * recently is the first row on the first page.
 *
 * <p>It was soonest-due-first, which read well and behaved badly. A newly
 * created task has no {@code due_at} until it activates, so the queue sorted
 * exactly the newest work <em>last</em> — and once the manager review gate
 * began adding submitted tasks to a reviewer's page, the things that had just
 * landed were the things pushed onto page two. A reader opening this screen is
 * asking "what is new", and the answer was at the bottom.
 *
 * <p>{@code created_at} is {@code NOT NULL} with a default, so the keyset
 * cursor has a column that is never null — which is what makes the
 * {@code (sortKey, id)} comparison a total order rather than a source of
 * skipped rows. The old coalesced sentinel existed only to buy that property
 * for a nullable column, and goes with it.
 *
 * <p>Lateness is not lost: {@code isOverdue} rides on every row and the due
 * date turns red, so late work is still visible — it is simply no longer what
 * decides the order.
 *
 * <h2>What the page deliberately does not filter out</h2>
 *
 * <p><b>Blocked and Waiting on client stay in.</b> They are still open and still
 * his; the status column says which, and a queue that hid them would hide the
 * work that has been stuck longest. <b>Tasks behind a locked prerequisite gate
 * stay in</b> too — the checklist reports, it does not hold, and the project
 * page shows them for the same reason.
 *
 * <p>What is excluded is work nobody expects: a project that is
 * {@code ON_HOLD} or {@code DROPPED} has had its clock stopped on purpose
 * ({@code ObProjectStatus.accruesDelay}), so listing its tasks would be asking
 * somebody to do something the organisation decided to stop.
 */
@Repository
class ObMyTaskReadRepository {

    /**
     * The ways a task is yours, in one place.
     *
     * <p>Shared by the page and the single read so the two cannot come to
     * disagree about who owns what — which would be the difference between a
     * task somebody can see in their own queue and a 404 when they open it.
     *
     * <h2>Three ways it is your work</h2>
     *
     * <p>Owner, backup owner, or inherited — see the class javadoc.
     *
     * <h2>And one way it is your <em>review</em></h2>
     *
     * <p>A task in {@code PENDING_REVIEW} on a project whose
     * {@code implementor_manager_user_id} is the caller. That is the manager
     * review gate arriving on the screen that already answers "what is open
     * against me": a submitted task is open against whoever has to read it,
     * and giving it a page of its own would mean two places to look for work
     * and one of them usually empty.
     *
     * <p><b>Named on the project, not a role.</b> This was once
     * `:reviewer = TRUE` for anybody holding {@code OB_MANAGER}, which put
     * every submitted task in the module on every manager's page. The project
     * carries who is accountable for it, and that is the narrower and truer
     * answer — a manager sees the engagements they own and nobody else's. The
     * write agrees: {@code ObJourneyStepLifecycleService#requireReviewer}
     * checks the same column, so this queue never lists a verdict the server
     * would refuse.
     *
     * <p>{@code :admin} is the one exception, and exists so a project whose
     * manager has left — or was never named — is not a review nobody on earth
     * can close.
     *
     * <p>The clause is deliberately <em>outside</em> the ownership group. A
     * manager's own tasks still reach them by the first three; this adds to
     * the queue rather than replacing it, so somebody who both implements and
     * manages sees both kinds on one page — which is what My Tasks has always
     * promised.
     */
    private static final String MINE = """
            js.owner_user_id = :me
                 OR js.backup_owner_user_id = :me
                 OR (js.owner_user_id IS NULL
                     AND js.backup_owner_user_id IS NULL
                     AND p.implementor_user_id = :me)
                 OR ((p.implementor_manager_user_id = :me OR :admin = TRUE)
                     AND js.status = 'PENDING_REVIEW')
            """;

    /** Columns and joins, shared by both reads so one row shape serves both. */
    private static final String PROJECTION = """
            SELECT js.id                                       AS taskId,
                   js.name                                     AS taskName,
                   js.status                                   AS status,
                   js.due_at                                   AS dueAt,
                   js.created_at                               AS sortKey,
                   jr.id                                       AS journeyId,
                   jr.service_name                             AS serviceName,
                   p.id                                        AS projectId,
                   p.name                                      AS projectName,
                   cl.id                                       AS obClientId,
                   cl.name                                     AS obClientName,
                   cl.client_code                              AS obClientCode,
                   COALESCE(g.implementation_stage_id, -g.id, 0) AS stepKey,
                   COALESCE(g.name, 'Ungrouped')               AS stepName,
                   COALESCE(g.sequence, 9999)                  AS stepSequence,
                   -- What the highlighter on this row is about. Three counts
                   -- rather than one flag, because the three mean different
                   -- things to different readers: `rowsOut` is a manager's
                   -- queue and an implementor's wait, and the two unseen counts
                   -- are the signal that something came back.
                   --
                   -- Correlated subqueries, and deliberately not a summary
                   -- table. CLAUDE.md's "never a live COUNT(*)" is about
                   -- dashboards — a figure the whole org reads, recomputed on
                   -- every page load. This is per-row detail on a keyset page
                   -- that is already bounded at twenty, and it has to be exact
                   -- the instant a row moves: a highlight that is five minutes
                   -- stale is worse than none, because it sends somebody to a
                   -- task with nothing on it.
                   (SELECT COUNT(*) FROM ob_journey_step_items oi
                     WHERE oi.step_id = js.id
                       AND oi.row_state = 'SENT')              AS rowsOut,
                   (SELECT COUNT(*) FROM ob_journey_step_items oi
                     WHERE oi.step_id = js.id
                       AND oi.row_state = 'REJECTED'
                       AND oi.outcome_seen_at IS NULL)         AS rowsReturned,
                   (SELECT COUNT(*) FROM ob_journey_step_items oi
                     WHERE oi.step_id = js.id
                       AND oi.row_state = 'VERIFIED'
                       AND oi.outcome_seen_at IS NULL)         AS rowsApproved,
                   -- The fourth `MINE` clause, isolated: true exactly when
                   -- this row is here because the caller must verify it
                   -- (status PENDING_REVIEW, caller named manager or admin)
                   -- rather than because the caller owns it. Without this a
                   -- PENDING_REVIEW row looks the same on a manager's page
                   -- and its owner's, and the two mean opposite things — one
                   -- is a wait, the other is a queue to clear.
                   ((p.implementor_manager_user_id = :me OR :admin = TRUE)
                     AND js.status = 'PENDING_REVIEW')          AS pendingMyVerification
              FROM ob_journey_steps js
              JOIN ob_journeys jr ON jr.id = js.journey_id
                                 AND jr.archived_at IS NULL
              JOIN ob_projects p  ON p.id = jr.project_id
              JOIN ob_clients cl  ON cl.id = jr.ob_client_id
         LEFT JOIN ob_journey_template_steps  ts ON ts.id = js.template_step_id
         LEFT JOIN ob_journey_template_stages g  ON g.id = ts.template_stage_id
            """;

    private static final String PAGE = PROJECTION + """
             WHERE js.status NOT IN ('DONE', 'SKIPPED')
               AND p.status NOT IN ('ON_HOLD', 'DROPPED')
               AND (%s)
               AND (:cursorKey IS NULL
                 OR js.created_at < :cursorKey
                 OR (js.created_at = :cursorKey AND js.id < :cursorId))
          ORDER BY js.created_at DESC, js.id DESC
             LIMIT :limit
            """.formatted(MINE);

    private static final String FIND_ONE = PROJECTION + """
             WHERE js.id = :taskId
               AND (%s)
            """.formatted(MINE);

    private final JdbcClient jdbc;

    ObMyTaskReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param fetchSize one more than the page — {@code PageLimit.fetchSize}
     * @return up to {@code fetchSize} rows, soonest due first
     */
    List<Row> openTasksOf(long userId, boolean admin, Cursor cursor, int fetchSize) {
        return jdbc.sql(PAGE)
                .param("me", userId)
                .param("admin", admin)
                .param("cursorKey", cursor == null ? null : cursor.sortKey())
                .param("cursorId", cursor == null ? 0L : cursor.id())
                .param("limit", fetchSize)
                .query(MAPPER)
                .list();
    }

    /**
     * One task of the caller's, by id.
     *
     * <p>Neither the status nor the project-status filter applies here. The page
     * leaves out settled tasks because a queue is work still to do; this read
     * answers "show me this one", and a task just completed — or one on a
     * project since put on hold — is still theirs to look at. Following a link
     * and getting a 404 because the work is finished would read as the record
     * having been deleted.
     *
     * <p>A task that is <b>not</b> theirs answers empty, and the caller turns
     * that into a 404 rather than a 403: CONVENTIONS.md §7, and the reason every
     * scoped read here does it — a 403 confirms the task exists, and task ids
     * are sequential.
     */
    Optional<Row> findOwnTask(long userId, boolean admin, long taskId) {
        return jdbc.sql(FIND_ONE)
                .param("me", userId)
                .param("admin", admin)
                .param("taskId", taskId)
                .query(MAPPER)
                .optional();
    }

    /**
     * One open task of one implementor.
     *
     * @param sortKey               the key this row was ordered by — {@code created_at} — carried out of
     *                              SQL rather than recomputed, so the cursor the service
     *                              encodes is exactly the value the next page compares
     *                              against
     * @param pendingMyVerification true when this row reached the page through the
     *                              reviewer clause of {@link #MINE} rather than an
     *                              ownership one — see the column's own comment on
     *                              {@link #PROJECTION}
     */
    record Row(long taskId, String taskName, String status, Instant dueAt, String sortKey,
               long journeyId, String serviceName, long projectId, String projectName,
               long obClientId, String obClientName, String obClientCode,
               long stepKey, String stepName, int stepSequence,
               int rowsOut, int rowsReturned, int rowsApproved, boolean pendingMyVerification) {
    }

    private static final RowMapper<Row> MAPPER = (rs, n) -> new Row(
            rs.getLong("taskId"),
            rs.getString("taskName"),
            rs.getString("status"),
            instant(rs, "dueAt"),
            rs.getString("sortKey"),
            rs.getLong("journeyId"),
            rs.getString("serviceName"),
            rs.getLong("projectId"),
            rs.getString("projectName"),
            rs.getLong("obClientId"),
            rs.getString("obClientName"),
            rs.getString("obClientCode"),
            rs.getLong("stepKey"),
            rs.getString("stepName"),
            rs.getInt("stepSequence"),
            rs.getInt("rowsOut"),
            rs.getInt("rowsReturned"),
            rs.getInt("rowsApproved"),
            rs.getBoolean("pendingMyVerification"));

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /**
     * Whether the caller reviews at least one project — page-independent,
     * unlike {@code pendingMyVerification} on each row.
     *
     * <p>My Tasks' "Pending for verification" tab is drawn only for a caller
     * this answers {@code true} for. A row's own {@code pendingMyVerification}
     * cannot carry that decision: it says nothing about a manager whose
     * current ten rows happen to be their own work rather than a review, and
     * gating the tab on the page in hand would make it flicker off between
     * pages of one caller's own queue.
     *
     * <p>Same admin exception as {@link #MINE}'s reviewer clause: an
     * {@code OB_ADMIN} always reviews, which is the unsticking path for a
     * project whose named manager has left.
     */
    boolean reviewsAnyProject(long userId, boolean admin) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM ob_projects p
                             WHERE p.status NOT IN ('ON_HOLD', 'DROPPED')
                               AND (p.implementor_manager_user_id = :me OR :admin = TRUE)
                        )
                        """)
                .param("me", userId)
                .param("admin", admin)
                .query(Boolean.class)
                .single());
    }
}
