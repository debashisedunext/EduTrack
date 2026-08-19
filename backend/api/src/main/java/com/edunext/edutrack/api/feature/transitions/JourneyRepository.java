package com.edunext.edutrack.api.feature.transitions;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * C-058 · blueprint §4A.4's roll-up, as PLAN.md §3.4 rewrote it.
 *
 * <h2>Two corrections to the blueprint's own query, both deliberate</h2>
 *
 * <p><b>1. The effort join matches {@code cycle_no}.</b> The blueprint joins
 * effort logs on {@code (ticket_id, stage_code, iteration_no)} and stops there.
 * Reopen a ticket and it re-enters the same stage at the same iteration number
 * in cycle 2 — and cycle 1's hours are counted again against cycle 2's row.
 * PLAN.md §3.4 records this as a real defect in the blueprint rather than a
 * MySQL artefact, and {@code AND e.cycle_no = t.cycle_no} is the fix. It is the
 * single most important line in this file, and
 * {@code JourneyRepositoryIT.effortDoesNotLeakAcrossCyclesAfterAReopen} is the
 * test that would fail without it.
 *
 * <p><b>2. Every non-aggregated column is in the {@code GROUP BY}.</b> MySQL 8
 * runs with {@code ONLY_FULL_GROUP_BY} on, and the blueprint's query groups by
 * {@code t.id, u.full_name, u.role_code} while selecting five more columns.
 * PostgreSQL permits that because {@code t.id} functionally determines them;
 * MySQL's detection does not extend across this join. We do not switch the mode
 * off — PLAN.md §3.4 keeps it because it catches real bugs — so the query is
 * written out in full instead.
 *
 * <h2>A LEFT JOIN, not a JOIN, in two places</h2>
 *
 * <p>{@code users} is joined LEFT because §4A.2 lets a ticket fall to a
 * project-level queue when the receiving role has nobody free (C-050): an
 * unassigned hop is a real state, and an inner join would silently drop that
 * row from the journey — hiding exactly the stall the grid exists to show.
 * Effort is LEFT joined for the ordinary reason: a stage somebody passed
 * through without logging time still happened.
 *
 * <h2>Raw SQL rather than JPA</h2>
 *
 * <p>An aggregate over a join with a three-column correlated condition is not
 * what JPA is for, and 52 of this codebase's 75 data-access classes already use
 * {@link JdbcClient} for the same reason. It also keeps the corrected join
 * visible as SQL, where the next person to read §4A.5 and wonder why it differs
 * can see it.
 */
@Repository
class JourneyRepository {

    private final JdbcClient jdbc;

    JourneyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One row per hop in this cycle, oldest first — {@code seq_no} is the order they happened. */
    List<JourneyDtos.JourneyRow> hops(long ticketId, int cycleNo) {
        return jdbc.sql("""
                SELECT t.iteration_no, t.cycle_no, t.to_stage, t.entered_at, t.exited_at,
                       t.duration_mins, t.seq_no,
                       u.id AS user_id, u.full_name, r.code AS role_code,
                       COALESCE(SUM(e.hours), 0) AS effort_hours
                  FROM ticket_stage_transitions t
                  LEFT JOIN users u ON u.id = t.to_user_id
                  LEFT JOIN roles r ON r.id = u.role_id
                  LEFT JOIN ticket_effort_logs e
                         ON e.ticket_id    = t.ticket_id
                        AND e.stage_code   = t.to_stage
                        AND e.iteration_no = t.iteration_no
                        AND e.cycle_no     = t.cycle_no
                 WHERE t.ticket_id = :ticketId AND t.cycle_no = :cycleNo
                 GROUP BY t.id, t.iteration_no, t.cycle_no, t.to_stage, t.entered_at, t.exited_at,
                          t.duration_mins, t.seq_no, u.id, u.full_name, r.code
                 ORDER BY t.seq_no
                """)
                .param("ticketId", ticketId)
                .param("cycleNo", cycleNo)
                .query((rs, n) -> toRow(rs))
                .list();
    }

    /** Effort per person for this cycle, heaviest first. */
    List<JourneyDtos.PerResource> perResource(long ticketId, int cycleNo) {
        return jdbc.sql("""
                SELECT u.id AS user_id, u.full_name, SUM(e.hours) AS effort_hours
                  FROM ticket_effort_logs e
                  JOIN users u ON u.id = e.user_id
                 WHERE e.ticket_id = :ticketId AND e.cycle_no = :cycleNo
                 GROUP BY u.id, u.full_name
                 ORDER BY effort_hours DESC, u.full_name
                """)
                .param("ticketId", ticketId)
                .param("cycleNo", cycleNo)
                .query((rs, n) -> new JourneyDtos.PerResource(
                        new JourneyDtos.UserRef(rs.getLong("user_id"), rs.getString("full_name")),
                        scaled(rs.getBigDecimal("effort_hours"))))
                .list();
    }

    /**
     * This cycle's effort total.
     *
     * <p>Summed from {@code ticket_effort_logs} rather than read from
     * {@code ticket_cycles.effort_hrs}, unlike {@link #allCyclesTotalHrs}. The
     * materialised column is maintained by C-041 for the *current* cycle's
     * running total; this endpoint answers for whichever cycle was asked for,
     * including sealed ones, so it goes to the ledger the column is derived
     * from rather than trusting a figure that describes a different cycle.
     */
    BigDecimal cycleTotalHrs(long ticketId, int cycleNo) {
        return scaled(jdbc.sql("""
                SELECT COALESCE(SUM(hours), 0) FROM ticket_effort_logs
                 WHERE ticket_id = :ticketId AND cycle_no = :cycleNo
                """)
                .param("ticketId", ticketId)
                .param("cycleNo", cycleNo)
                .query(BigDecimal.class)
                .single());
    }

    /**
     * Every cycle's effort.
     *
     * <p>Summed here as well, for one reason worth stating: it must agree with
     * {@link #cycleTotalHrs} when a ticket has one cycle. Reading
     * {@code tickets.total_effort_hrs} for this and summing the log for the
     * other would let the two disagree the moment C-041's write path missed an
     * append — and the roll-up is where such a disagreement would first be
     * seen, so it should not be the place that hides it.
     */
    BigDecimal allCyclesTotalHrs(long ticketId) {
        return scaled(jdbc.sql("SELECT COALESCE(SUM(hours), 0) FROM ticket_effort_logs WHERE ticket_id = :ticketId")
                .param("ticketId", ticketId)
                .query(BigDecimal.class)
                .single());
    }

    private static JourneyDtos.JourneyRow toRow(ResultSet rs) throws SQLException {
        BigDecimal effort = scaled(rs.getBigDecimal("effort_hours"));

        long userId = rs.getLong("user_id");
        JourneyDtos.UserRef resource = rs.wasNull()
                ? null
                : new JourneyDtos.UserRef(userId, rs.getString("full_name"));

        Integer durationMins = (Integer) rs.getObject("duration_mins");

        return new JourneyDtos.JourneyRow(
                (int) rs.getShort("iteration_no"),
                (int) rs.getShort("cycle_no"),
                rs.getString("to_stage"),
                resource,
                rs.getString("role_code"),
                instantOf(rs, "entered_at"),
                instantOf(rs, "exited_at"),
                durationMins,
                effort,
                idleMins(durationMins, effort));
    }

    /**
     * §4A.4's second derived number, and the one the endpoint exists for.
     *
     * <p><b>Floored at zero rather than allowed negative.</b> Effort is logged
     * by hand against a stage; somebody booking three hours to a stage that was
     * open for two is a data-entry question, not a discovery that the ticket
     * waited negative time. Clamping keeps the column readable and leaves the
     * over-booking visible where it belongs — beside the effort figure itself.
     *
     * <p>Null when duration is null: an open hop has no elapsed span to
     * subtract from, and a zero here would read as "no waiting so far", which
     * is a claim nobody has measured.
     */
    private static Integer idleMins(Integer durationMins, BigDecimal effortHrs) {
        if (durationMins == null) {
            return null;
        }
        int effortMins = effortHrs.multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.HALF_UP).intValue();
        return Math.max(0, durationMins - effortMins);
    }

    private static java.time.Instant instantOf(ResultSet rs, String column) throws SQLException {
        var ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    /** Two decimals, the scale {@code ticket_effort_logs.hours} is stored at. */
    private static BigDecimal scaled(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
