package com.edunext.edutrack.api.feature.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * A-071 · reads over {@code audit_logs} for S-16.
 *
 * <p>Hand-written rather than derived, and separate from
 * {@code domain.audit.AuditLogRepository}, which stayed a two-finder interface.
 * Five optional filters, a keyset cursor and a join for the actor's name is not
 * a method name Spring Data can spell, and the attempt would be a
 * {@code Specification} that nobody can read the SQL of — over the one table in
 * the schema that grows without bound and whose query plan therefore matters
 * most.
 *
 * <h2>No {@code SELECT COUNT(*)}, and no route to one</h2>
 *
 * <p>{@code Meta.totalCount} exists in the contract and is documented there as
 * "present only where a count is cheap". Counting this table is the opposite of
 * cheap and gets worse every day the product runs; the screen shows "showing
 * the most recent N" and a Load more, which is the honest thing a keyset page
 * can say.
 *
 * <h2>Everything is a bound parameter</h2>
 *
 * <p>Including {@code action} and {@code entityType}, which arrive as free text
 * from the query string. Nothing in this file concatenates a caller's value
 * into SQL — the vocabulary is open-ended by design (a route added next month
 * produces a term nobody wrote down), so there is no enum to validate against
 * and the parameter binding is what has to carry it.
 */
@Repository
class AuditQueryRepository {

    /**
     * The keyset page.
     *
     * <p>{@code (created_at, id)} as a pair, both descending, matching
     * {@code ix_audit_logs_recent}. Ordering on {@code created_at} alone would
     * be ambiguous for rows written in the same microsecond — and this table's
     * writes arrive in bursts, because one user action can be several requests
     * — so paging past such a group would repeat or skip rows depending on how
     * MySQL felt about the tie.
     *
     * <p>The comparison is written as the expanded {@code (a < :x OR (a = :x
     * AND b < :y))} rather than MySQL's row-value form {@code (a, b) < (:x,
     * :y)}. The row-value form is tidier and is not used because the optimiser
     * treats it as a filter rather than a range scan on this index, which turns
     * every page after the first into a scan of everything newer than the
     * cursor.
     */
    private static final String PAGE = """
            SELECT a.id            AS id,
                   a.actor_id      AS actor_id,
                   u.full_name     AS actor_name,
                   r.code          AS actor_role,
                   a.action        AS action,
                   a.entity_type   AS entity_type,
                   a.entity_id     AS entity_id,
                   a.entity_ref    AS entity_ref,
                   a.old_value     AS old_value,
                   a.new_value     AS new_value,
                   a.ip_address    AS ip_address,
                   a.user_agent    AS user_agent,
                   a.created_at    AS created_at
              FROM audit_logs a
              LEFT JOIN users u ON u.id = a.actor_id
              LEFT JOIN roles r ON r.id = u.role_id
             WHERE (:actorId    IS NULL OR a.actor_id    =  :actorId)
               AND (:action     IS NULL OR a.action      =  :action)
               AND (:entityType IS NULL OR a.entity_type =  :entityType)
               AND (:from       IS NULL OR a.created_at  >= :from)
               AND (:to         IS NULL OR a.created_at  <  :to)
               AND (:cursorAt   IS NULL OR a.created_at  <  :cursorAt
                    OR (a.created_at = :cursorAt AND a.id < :cursorId))
             ORDER BY a.created_at DESC, a.id DESC
             LIMIT :limit
            """;

    private final JdbcClient jdbc;

    AuditQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One row as stored, before {@link AuditService} decides how to present it.
     *
     * <p>{@code actorName} is null for a SYSTEM row <em>and</em> for a row whose
     * actor has since been removed from {@code users} — the join is a
     * {@code LEFT JOIN} on purpose, because an audit row must survive its actor.
     * The two are distinguished by {@code actorId}: null means SYSTEM, non-null
     * with no name means the account is gone, and the viewer says so rather
     * than dropping the row.
     */
    record Row(long id, Long actorId, String actorName, String actorRole,
               String action, String entityType, Long entityId, String entityRef,
               String oldValue, String newValue,
               String ipAddress, String userAgent, Instant createdAt) {
    }

    /**
     * @param limit  already clamped by {@code PageLimit}, and already the
     *               fetch-one-extra size — this method does not add the extra
     *               row itself, because {@code CursorPage.of} is what decides
     *               where the boundary is and only one place may
     * @param cursorAt the previous page's last {@code created_at}, or null for
     *                 the first page; {@code cursorId} breaks its ties
     */
    List<Row> page(Long actorId, String action, String entityType,
                   Instant from, Instant to,
                   Instant cursorAt, Long cursorId, int limit) {
        return jdbc.sql(PAGE)
                .param("actorId", actorId)
                .param("action", action)
                .param("entityType", entityType)
                // Timestamp rather than Instant: the column is DATETIME(6) with
                // no zone, storage is UTC (PLAN.md §3.1), and the driver's
                // Instant binding would apply the session zone on the way in.
                .param("from", from == null ? null : Timestamp.from(from))
                .param("to", to == null ? null : Timestamp.from(to))
                .param("cursorAt", cursorAt == null ? null : Timestamp.from(cursorAt))
                .param("cursorId", cursorId)
                .param("limit", limit)
                .query((rs, n) -> new Row(
                        rs.getLong("id"),
                        nullableLong(rs, "actor_id"),
                        rs.getString("actor_name"),
                        rs.getString("actor_role"),
                        rs.getString("action"),
                        rs.getString("entity_type"),
                        nullableLong(rs, "entity_id"),
                        rs.getString("entity_ref"),
                        rs.getString("old_value"),
                        rs.getString("new_value"),
                        rs.getString("ip_address"),
                        rs.getString("user_agent"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /**
     * {@code ResultSet.getLong} answers 0 for SQL NULL, and 0 reads on a screen
     * as an id rather than as an absence. Read the primitive, then ask whether
     * it was null — which is what keeps a SYSTEM row from being rendered as
     * user 0 and an entity-less action from claiming a subject.
     */
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
