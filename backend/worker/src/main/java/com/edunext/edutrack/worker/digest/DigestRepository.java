package com.edunext.edutrack.worker.digest;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * D-038 · who has open work, and how much of it is late.
 *
 * <p>One row per person with something to say, never one per person. A digest
 * that arrives saying "you have 0 open tickets" is how somebody learns to
 * filter digests, and then misses the one that mattered — so the {@code HAVING}
 * clause is a product decision rather than an optimisation.
 *
 * <p><strong>Counted here rather than in Java.</strong> The alternative is
 * loading every open ticket into the worker to group them, which is the whole
 * ticket table for the sake of three integers per person. This is also not the
 * "no live COUNT(*) for dashboards" case — that rule is about a screen somebody
 * is waiting on, read from pre-aggregated tables. This runs once a day, off a
 * clock, with nobody waiting, and the summary tables are keyed by day rather
 * than by assignee-and-due-date.
 */
@Repository
public class DigestRepository {

    /**
     * The day boundaries are passed in as UTC instants rather than computed
     * with MySQL date functions, because "today" is a question about the
     * organisation's zone and the database stores UTC. Doing it in SQL would
     * put a second, silent timezone assumption next to the calendar's.
     */
    private static final String DAILY = """
            SELECT u.id            AS userId,
                   u.email         AS email,
                   u.full_name     AS fullName,
                   COUNT(*)                                                    AS openCount,
                   SUM(t.planned_close_date >= :dayStart
                       AND t.planned_close_date < :dayEnd)                     AS dueToday,
                   SUM(t.planned_close_date < :dayStart)                       AS overdue
              FROM tickets t
              JOIN users u ON u.id = t.assigned_to
             WHERE t.status NOT IN ('CLOSED', 'RESOLVED')
               AND u.is_active = 1
               AND u.email IS NOT NULL
               AND t.planned_close_date IS NOT NULL
             GROUP BY u.id, u.email, u.full_name
            HAVING openCount > 0
             ORDER BY overdue DESC, dueToday DESC, u.id
            """;

    /**
     * §4B.6 addresses the weekly summary to "RM, PM". Scoped by project
     * membership rather than by role alone: a PM with no project has no team to
     * summarise, and would otherwise receive a mail about everybody.
     */
    private static final String WEEKLY = """
            SELECT m.user_id       AS userId,
                   u.email         AS email,
                   u.full_name     AS fullName,
                   COUNT(*)                                                    AS openCount,
                   SUM(t.planned_close_date < :dayStart)                       AS overdue,
                   SUM(t.level = 'CRITICAL')                                   AS critical
              FROM project_members m
              JOIN users u ON u.id = m.user_id
              JOIN tickets t ON t.project_id = m.project_id
             WHERE m.role_in_project = 'PM'
               AND u.is_active = 1
               AND u.email IS NOT NULL
               AND t.status NOT IN ('CLOSED', 'RESOLVED')
               AND t.planned_close_date IS NOT NULL
             GROUP BY m.user_id, u.email, u.full_name
            HAVING openCount > 0
             ORDER BY overdue DESC, m.user_id
            """;

    private final JdbcClient jdbc;

    DigestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One assignee's day. {@code dayStart}/{@code dayEnd} bound today in the calendar's zone. */
    public List<Assignee> assigneesWithOpenWork(Instant dayStart, Instant dayEnd) {
        return jdbc.sql(DAILY)
                .param("dayStart", java.sql.Timestamp.from(dayStart))
                .param("dayEnd", java.sql.Timestamp.from(dayEnd))
                .query(Assignee.class)
                .list();
    }

    /** One PM's week. */
    public List<Manager> managersWithOpenWork(Instant dayStart) {
        return jdbc.sql(WEEKLY)
                .param("dayStart", java.sql.Timestamp.from(dayStart))
                .query(Manager.class)
                .list();
    }

    public record Assignee(long userId, String email, String fullName,
                           int openCount, int dueToday, int overdue) {
    }

    public record Manager(long userId, String email, String fullName,
                          int openCount, int overdue, int critical) {
    }
}
