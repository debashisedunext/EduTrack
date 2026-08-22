package com.edunext.edutrack.api.feature.reports;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * B-065 · the write side of {@code timesheet_approvals} (V20260822_1200).
 *
 * <p>Separate from {@link TimesheetRepository} on {@code ResourceWriteRepository}'s
 * own precedent: that class is a read projection over {@code ticket_effort_logs},
 * this one is the statement that changes a different table. There is
 * deliberately no {@code update} or {@code delete} here — a week is approved
 * once, and this repository offers no way to make it not have been.
 */
@Repository
class TimesheetApprovalRepository {

    private static final String FIND = """
            SELECT ta.id, ta.user_id, ta.week_start_date, ta.approved_by_id, ta.approved_at, ta.note,
                   u.full_name AS approved_by_name, u.username AS approved_by_username,
                   r.code AS approved_by_role
              FROM timesheet_approvals ta
              JOIN users u ON u.id = ta.approved_by_id
              JOIN roles r ON r.id = u.role_id
             WHERE ta.user_id = ? AND ta.week_start_date = ?
            """;

    private static final String INSERT = """
            INSERT INTO timesheet_approvals (user_id, week_start_date, approved_by_id, approved_at, note)
            VALUES (?, ?, ?, ?, ?)
            """;

    private final JdbcClient jdbc;

    TimesheetApprovalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Approval> find(long userId, LocalDate weekStart) {
        return jdbc.sql(FIND).param(userId).param(weekStart).query(this::mapRow).optional();
    }

    /**
     * @return the new row, or empty when {@code (userId, weekStart)} was
     *         already approved — {@code uq_timesheet_approvals_week}'s race,
     *         caught rather than left to surface as an uncategorised SQL
     *         exception. The caller re-reads with {@link #find} to report who
     *         got there first.
     */
    Optional<Long> insert(long userId, LocalDate weekStart, long approvedById, Instant approvedAt, String note) {
        try {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.sql(INSERT)
                    .param(userId)
                    .param(weekStart)
                    .param(approvedById)
                    .param(java.sql.Timestamp.from(approvedAt))
                    .param(note)
                    .update(keys);
            return Optional.of(keys.getKey().longValue());
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    private Approval mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Approval(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getObject("week_start_date", LocalDate.class),
                new TimesheetDtos.UserRef(
                        rs.getLong("approved_by_id"),
                        rs.getString("approved_by_name"),
                        rs.getString("approved_by_role"),
                        rs.getString("approved_by_username")),
                rs.getTimestamp("approved_at").toInstant(),
                rs.getString("note"));
    }

    record Approval(long id, long userId, LocalDate weekStart, TimesheetDtos.UserRef approvedBy,
                    Instant approvedAt, String note) {
    }
}
