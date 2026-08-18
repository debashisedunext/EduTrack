package com.edunext.edutrack.api.feature.reports;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * A-069 · what S-28's profile reads.
 *
 * <p>The visibility question is here rather than in the service because it is a
 * <b>query</b>, not a policy check: whether a PM may see somebody depends on
 * rows — who reports to whom, and who is on which project. Answering it in Java
 * would mean fetching both sets and intersecting them in memory, which is the
 * shape that quietly stops being applied when somebody adds a caller.
 */
@Repository
class Profile360Repository {

    private final JdbcClient jdbc;

    Profile360Repository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The subject's own record, or empty if there is no such active user.
     *
     * <p>Empty becomes a 404 at the controller. Deliberately the same answer as
     * "you may not see this person" — see {@link #isVisibleTo}.
     */
    Optional<Subject> subject(long userId) {
        return jdbc.sql("""
                        SELECT u.id, u.full_name, u.username, u.email, r.code AS role_code,
                               u.department, u.designation, u.is_active, u.date_of_joining,
                               m.full_name AS manager_name
                          FROM users u
                          JOIN roles r ON r.id = u.role_id
                          LEFT JOIN users m ON m.id = u.reporting_manager_id
                         WHERE u.id = :userId
                        """)
                .param("userId", userId)
                .query((rs, n) -> new Subject(
                        rs.getLong("id"), rs.getString("full_name"), rs.getString("username"),
                        rs.getString("email"), rs.getString("role_code"), rs.getString("department"),
                        rs.getString("designation"), rs.getBoolean("is_active"),
                        rs.getObject("date_of_joining", LocalDate.class),
                        rs.getString("manager_name")))
                .optional();
    }

    record Subject(long id, String fullName, String username, String email, String roleCode,
                   String department, String designation, boolean active, LocalDate joinedOn,
                   String managerName) {
    }

    /**
     * Blueprint §2's "View team member history" row, as a query.
     *
     * <p>Admin ✅ · PM ✅ (reportees + project) · Support ✅ (own project) ·
     * Developer, QA, Deployment ❌.
     *
     * <p><b>Plus yourself, always.</b> §2's row is about seeing <em>other</em>
     * people, and the three delivery roles are excluded from that. Refusing a
     * Developer their own profile would contradict the same table's Reports row,
     * which grants them "Own perf." — and the profile is where that lives.
     *
     * <p>PM is deliberately <em>reportees <b>or</b> project</em>, not both. §2
     * writes "reportees + project", and read as an intersection it would deny a
     * PM the profile of somebody who reports to them but works on another
     * project — which is exactly the person a manager most needs to look at.
     *
     * <p>Reportees are direct only. The contract has a {@code depth} parameter on
     * {@code /users/{id}/reportees} and this does not use it: a transitive walk
     * would let a senior manager see everybody through a chain of one-to-ones,
     * which is a policy decision nobody has taken. Direct reports plus shared
     * projects is the conservative reading, and widening it later is additive.
     */
    boolean isVisibleTo(long subjectId, long viewerId, String viewerRole) {
        if (subjectId == viewerId) {
            return true;
        }
        if ("ADMIN".equals(viewerRole)) {
            return true;
        }
        if (!"PM".equals(viewerRole) && !"SUPPORT".equals(viewerRole)) {
            // The three delivery roles see nobody but themselves.
            return false;
        }

        Boolean visible = jdbc.sql("""
                        SELECT EXISTS (
                                   SELECT 1 FROM users u
                                    WHERE u.id = :subjectId
                                      AND u.reporting_manager_id = :viewerId)
                            OR EXISTS (
                                   SELECT 1
                                     FROM project_members mine
                                     JOIN project_members theirs
                                       ON theirs.project_id = mine.project_id
                                    WHERE mine.user_id = :viewerId
                                      AND theirs.user_id = :subjectId) AS visible
                        """)
                .param("subjectId", subjectId)
                .param("viewerId", viewerId)
                .query(Boolean.class)
                .single();

        return Boolean.TRUE.equals(visible);
    }

    /**
     * The headline figures, over the window the screen asks for.
     *
     * <p>Closed, effort and the two rates all come from {@code tickets} and the
     * effort log, for A-066's reason: cycle time, SLA compliance and a rework
     * rate are per-ticket facts no summary table carries at any grain.
     */
    Headline headline(long userId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT (SELECT COUNT(*) FROM tickets o
                                 WHERE o.assigned_to = :userId AND o.status <> 'CLOSED') AS open_now,
                               (SELECT COUNT(*) FROM tickets c
                                 WHERE c.assigned_to = :userId
                                   AND c.actual_close_date >= :from
                                   AND c.actual_close_date < :toExclusive)               AS closed,
                               (SELECT COUNT(*) FROM tickets c
                                 WHERE c.assigned_to = :userId
                                   AND c.actual_close_date >= :from
                                   AND c.actual_close_date < :toExclusive
                                   AND c.planned_close_date IS NOT NULL)                 AS committed,
                               (SELECT COUNT(*) FROM tickets c
                                 WHERE c.assigned_to = :userId
                                   AND c.actual_close_date >= :from
                                   AND c.actual_close_date < :toExclusive
                                   AND c.planned_close_date IS NOT NULL
                                   AND c.actual_close_date <= c.planned_close_date)      AS on_time,
                               (SELECT COUNT(*) FROM tickets c
                                 WHERE c.assigned_to = :userId
                                   AND c.actual_close_date >= :from
                                   AND c.actual_close_date < :toExclusive
                                   AND c.reopen_count > 0)                               AS reopened,
                               (SELECT COALESCE(SUM(e.hours), 0) FROM ticket_effort_logs e
                                 WHERE e.user_id = :userId
                                   AND e.work_date BETWEEN :from AND :to)                AS effort_hours
                        """)
                .param("userId", userId)
                .param("from", from)
                .param("to", to)
                // Exclusive, so work closed on the last day of the range is not
                // silently dropped by a DATETIME compared against midnight.
                .param("toExclusive", to.plusDays(1))
                .query((rs, n) -> new Headline(
                        rs.getLong("open_now"), rs.getLong("closed"), rs.getLong("committed"),
                        rs.getLong("on_time"), rs.getLong("reopened"), rs.getBigDecimal("effort_hours")))
                .single();
    }

    record Headline(long openNow, long closed, long committed, long onTime,
                    long reopened, BigDecimal effortHours) {
    }

    /**
     * Which ribbon stages this person's open work is sitting at.
     *
     * <p>§S-28 asks for "current open tickets"; the list itself comes from the
     * ticket list endpoint, which already paginates and scopes. What the profile
     * adds is the shape of it — a manager opening this wants to see that eleven
     * of somebody's fourteen are stuck in one stage, which a paginated list of
     * fourteen rows does not show on the first page.
     */
    List<StageCount> currentStages(long userId) {
        return jdbc.sql("""
                        SELECT COALESCE(t.current_stage, '(none)') AS stage_code,
                               COUNT(*) AS open_count
                          FROM tickets t
                         WHERE t.assigned_to = :userId
                           AND t.status <> 'CLOSED'
                      GROUP BY t.current_stage
                      ORDER BY open_count DESC
                        """)
                .param("userId", userId)
                .query((rs, n) -> new StageCount(rs.getString("stage_code"), rs.getLong("open_count")))
                .list();
    }

    record StageCount(String stageCode, long openCount) {
    }
}
