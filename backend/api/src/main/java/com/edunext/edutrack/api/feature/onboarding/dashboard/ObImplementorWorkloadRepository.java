package com.edunext.edutrack.api.feature.onboarding.dashboard;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * B-128 · the read behind {@code /dashboard/implementor-workload} — plan §9's
 * second grid, over B-120's {@code ob_implementor_daily_stats}.
 *
 * <h2>Not a live aggregate</h2>
 *
 * <p>Every workload and completion column is read straight off the
 * pre-aggregated row {@code ObDashboardStatsRepository} wrote — CLAUDE.md's
 * rule in its most literal form, since this table exists for exactly this
 * route. Nothing here sums {@code ob_journey_steps}.
 *
 * <h2>The bench is a row, not an absence</h2>
 *
 * <p>A-108's migration and {@code ObDashboardStatsRepository.refreshImplementorStock}
 * both make the population obligation explicit: an implementor holding a live
 * {@code OB_STEP_OWNER} grant gets a row even at {@code clients_open = 0}.
 * That is guaranteed by <em>how the row was written</em>, not by this query —
 * this class only ever selects rows that already exist and joins
 * {@code user_module_access} to answer {@link ObDashboardDtos.ObImplementorWorkload#isActive},
 * never to decide whether a row belongs on the page.
 *
 * <h2>{@code performanceScore} is not a column here either</h2>
 *
 * <p>{@link ObImplementorWorkloadService} derives it from the four counters
 * this class returns — see that class for the formula and why it is computed
 * on read rather than stored.
 */
@Repository
class ObImplementorWorkloadRepository {

    private static final String PAGE_SQL = """
            SELECT s.stat_date         AS stat_date,
                   s.user_id           AS user_id,
                   u.full_name         AS full_name,
                   s.clients_open      AS clients_open,
                   s.on_track          AS on_track,
                   s.not_started       AS not_started,
                   s.`delayed`         AS `delayed`,
                   s.at_risk           AS at_risk,
                   s.blocked_waiting   AS blocked_waiting,
                   s.ahead_of_schedule AS ahead_of_schedule,
                   s.completed_on_time AS completed_on_time,
                   s.completed_early   AS completed_early,
                   s.completed_late    AS completed_late,
                   s.blocked_hours     AS blocked_hours,
                   EXISTS (
                       SELECT 1 FROM user_module_access uma
                        WHERE uma.user_id = s.user_id AND uma.module = 'ONBOARDING'
                          AND uma.module_role = 'OB_STEP_OWNER' AND uma.revoked_at IS NULL
                   ) AS is_active
              FROM ob_implementor_daily_stats s
              JOIN users u ON u.id = s.user_id
             WHERE s.stat_date = :statDate
               AND (%1$s)
               AND (:includeInactive = TRUE OR EXISTS (
                       SELECT 1 FROM user_module_access uma2
                        WHERE uma2.user_id = s.user_id AND uma2.module = 'ONBOARDING'
                          AND uma2.module_role = 'OB_STEP_OWNER' AND uma2.revoked_at IS NULL))
               AND (:cursorName IS NULL
                    OR u.full_name > :cursorName
                    OR (u.full_name = :cursorName AND s.user_id > :cursorId))
          ORDER BY u.full_name ASC, s.user_id ASC
             LIMIT :fetchSize
            """;

    private final JdbcClient jdbc;

    ObImplementorWorkloadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The most recently computed day, or empty when B-120 has never run. */
    Optional<LocalDate> latestStatDate() {
        return jdbc.sql("SELECT MAX(stat_date) FROM ob_implementor_daily_stats")
                .query(LocalDate.class)
                .optional();
    }

    List<Row> page(ObDashboardScope scope, LocalDate statDate, boolean includeInactive,
            String cursorName, Long cursorId, int fetchSize) {

        if (scope.deniesEverything()) {
            return List.of();
        }
        String sql = PAGE_SQL.formatted(scope.implementorPredicate("s"));
        var spec = jdbc.sql(sql)
                .param("statDate", statDate)
                .param("includeInactive", includeInactive)
                .param("cursorName", cursorName)
                .param("cursorId", cursorId)
                .param("fetchSize", fetchSize);
        if (!scope.unrestricted()) {
            spec = spec.param(ObDashboardScope.USER_PARAM, scope.userId());
        }
        return spec.query(ROW_MAPPER).list();
    }

    /** One row of {@code ob_implementor_daily_stats}, before the DTO's derived {@code performanceScore}. */
    record Row(LocalDate statDate, long userId, String fullName, int clientsOpen, int onTrack, int notStarted,
              int delayed, int atRisk, int blockedWaiting, int aheadOfSchedule,
              int completedOnTime, int completedEarly, int completedLate, int blockedHours, boolean isActive) {
    }

    private static final RowMapper<Row> ROW_MAPPER = (rs, n) -> new Row(
            localDate(rs, "stat_date"),
            rs.getLong("user_id"),
            rs.getString("full_name"),
            rs.getInt("clients_open"),
            rs.getInt("on_track"),
            rs.getInt("not_started"),
            rs.getInt("delayed"),
            rs.getInt("at_risk"),
            rs.getInt("blocked_waiting"),
            rs.getInt("ahead_of_schedule"),
            rs.getInt("completed_on_time"),
            rs.getInt("completed_early"),
            rs.getInt("completed_late"),
            rs.getInt("blocked_hours"),
            rs.getBoolean("is_active"));

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }
}
