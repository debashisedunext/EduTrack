package com.edunext.edutrack.api.feature.onboarding.moduleaccess;

import com.edunext.edutrack.common.pagination.Cursor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * A-117 · {@code user_module_access} — OB-08's list, grant and revoke.
 *
 * <h2>SQL rather than JPA, and the generated column is why</h2>
 *
 * <p>A-109 gives this table {@code live_key TINYINT(1) GENERATED ALWAYS AS
 * (IF(revoked_at IS NULL, 1, NULL)) STORED}, carrying the partial unique index
 * that allows one live grant per (user, module). Hibernate would need
 * {@code @Generated} plus a refresh after every write to keep an entity's view
 * of it honest, and an entity that writes to it at all is a schema error at
 * runtime. The column is never selected here — it is the index's business, not
 * the application's.
 *
 * <p>It is also the idiom the table already has: {@code AuthUserRepository}
 * (A-110, A-112) reads the same rows as SQL to mint the {@code modules} and
 * {@code moduleRoles} claims. A JPA mapping alongside would be a second
 * description of one table, and the two would drift.
 *
 * <h2>There is no delete, and none may be added</h2>
 *
 * <p>Revoking stamps {@code revoked_at} and {@code revoked_by}. The whole
 * reason A-109 chose that over a delete is that an access audit is run
 * <em>after</em> something has been seen that should not have been, and asks
 * who could see the module last month. A delete answers nothing, and a method
 * that existed here would eventually be called.
 */
@Repository
class ObModuleAccessRepository {

    /**
     * The projection OB-08 renders, with both audit actors resolved to names.
     *
     * <p>Three {@code LEFT JOIN}s to {@code users}: the grantee, the granter
     * and the revoker. All three are outer — {@code granted_by} and
     * {@code revoked_by} are nullable by design ("null on a grant made by a
     * migration rather than by a person"), and an inner join on either would
     * silently drop the seed grant that gives the first administrator their own
     * access, which is precisely the row an auditor looks for first.
     *
     * <p>The grantee join is outer too, though {@code user_id} is not
     * nullable. A user row removed under a grant would otherwise make the grant
     * vanish from the audit rather than show up needing attention.
     */
    private static final String SELECT_GRANT = """
            SELECT uma.id,
                   uma.user_id,
                   gu.full_name        AS user_name,
                   uma.module,
                   uma.module_role,
                   uma.granted_by,
                   bu.full_name        AS granted_by_name,
                   uma.granted_at,
                   uma.revoked_at,
                   uma.revoked_by,
                   ru.full_name        AS revoked_by_name
              FROM user_module_access uma
              LEFT JOIN users gu ON gu.id = uma.user_id
              LEFT JOIN users bu ON bu.id = uma.granted_by
              LEFT JOIN users ru ON ru.id = uma.revoked_by
            """;

    /**
     * OB-08's page.
     *
     * <p>Every filter is {@code :p IS NULL OR column = :p}. Four optional
     * equality filters do not earn a criteria builder, and the shape keeps one
     * statement in the log rather than sixteen.
     *
     * <p>{@code includeRevoked} is the one that is not a column filter: off, it
     * means live only, which is the everyday question. On, the audit question.
     *
     * <h3>The keyset, and why it carries the id</h3>
     *
     * <p>Ordered {@code granted_at DESC, id DESC} so the newest grant reads
     * first. {@code granted_at} is {@code DATETIME(6)} and two grants made by
     * one admin clicking twice can share a microsecond, so the id breaks the
     * tie and makes the order total — without it a page boundary landing
     * between two equal timestamps repeats or skips a row.
     */
    private static final String SEARCH = SELECT_GRANT + """
             WHERE (:userId     IS NULL OR uma.user_id     = :userId)
               AND (:module     IS NULL OR uma.module      = :module)
               AND (:moduleRole IS NULL OR uma.module_role = :moduleRole)
               AND (:includeRevoked = TRUE OR uma.revoked_at IS NULL)
               AND (:cursorAt IS NULL
                    OR uma.granted_at < :cursorAt
                    OR (uma.granted_at = :cursorAt AND uma.id < :cursorId))
             ORDER BY uma.granted_at DESC, uma.id DESC
             LIMIT :limit
            """;

    private static final String FIND_BY_ID = SELECT_GRANT + " WHERE uma.id = :id";

    private static final String FIND_LIVE = SELECT_GRANT + """
             WHERE uma.user_id = :userId
               AND uma.module = :module
               AND uma.revoked_at IS NULL
            """;

    private static final String INSERT = """
            INSERT INTO user_module_access (user_id, module, module_role, granted_by, granted_at)
            VALUES (:userId, :module, :moduleRole, :grantedBy, :grantedAt)
            """;

    /**
     * <p>{@code AND revoked_at IS NULL} is not decoration. It makes the revoke
     * a compare-and-set: two admins revoking one grant concurrently both pass a
     * prior read, and the second update matches nothing rather than overwriting
     * the first revoker's name with its own. The service reads the row count
     * and answers 422 — the same answer the sequential case gets.
     */
    private static final String REVOKE = """
            UPDATE user_module_access
               SET revoked_at = :revokedAt, revoked_by = :revokedBy
             WHERE id = :id AND revoked_at IS NULL
            """;

    /**
     * The count behind "this is the last live OB_ADMIN grant".
     *
     * <p>Not filtered by module: {@code OB_ADMIN} is only ever granted on
     * {@code ONBOARDING} — {@code ck_user_module_access_module_role} closes the
     * vocabulary and no other module uses the value — so adding the predicate
     * would narrow nothing while implying the count is per-module. If a second
     * module ever adopts the role this is a line to revisit, and a count that
     * silently excluded it would be worse than one that over-counts.
     */
    private static final String COUNT_LIVE_ADMINS = """
            SELECT COUNT(*) FROM user_module_access
             WHERE module_role = 'OB_ADMIN' AND revoked_at IS NULL
            """;

    private static final String ACTIVE_USER = """
            SELECT COUNT(*) FROM users WHERE id = :id AND is_active = 1
            """;

    private static final RowMapper<Row> MAPPER = ObModuleAccessRepository::mapRow;

    private final JdbcClient jdbc;

    ObModuleAccessRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Row> search(Long userId, String module, String moduleRole, boolean includeRevoked,
                     String cursor, int limit) {

        Cursor decoded = decodeCursor(cursor);
        return jdbc.sql(SEARCH)
                .param("userId", userId)
                .param("module", module)
                .param("moduleRole", moduleRole)
                .param("includeRevoked", includeRevoked)
                .param("cursorAt", decoded == null ? null : Timestamp.from(Instant.parse(decoded.sortKey())))
                .param("cursorId", decoded == null ? null : decoded.id())
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }

    Optional<Row> findById(long id) {
        return jdbc.sql(FIND_BY_ID).param("id", id).query(MAPPER).optional();
    }

    Optional<Row> findLiveGrant(long userId, String module) {
        return jdbc.sql(FIND_LIVE)
                .param("userId", userId)
                .param("module", module)
                .query(MAPPER)
                .optional();
    }

    long insert(long userId, String module, String moduleRole, long grantedBy, Instant grantedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql(INSERT)
                .param("userId", userId)
                .param("module", module)
                .param("moduleRole", moduleRole)
                .param("grantedBy", grantedBy)
                .param("grantedAt", Timestamp.from(grantedAt))
                .update(keys);
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("user_module_access insert returned no generated id");
        }
        return id.longValue();
    }

    /** @return rows changed — {@code 0} means it was already revoked. */
    int revoke(long id, long revokedBy, Instant revokedAt) {
        return jdbc.sql(REVOKE)
                .param("id", id)
                .param("revokedBy", revokedBy)
                .param("revokedAt", Timestamp.from(revokedAt))
                .update();
    }

    long countLiveAdminGrants() {
        return jdbc.sql(COUNT_LIVE_ADMINS).query(Long.class).single();
    }

    boolean isActiveUser(long userId) {
        return jdbc.sql(ACTIVE_USER).param("id", userId).query(Long.class).single() > 0;
    }

    /**
     * A cursor whose sort key is not a timestamp is treated as absent, exactly
     * as {@link Cursor#decode} treats a forged one: it means the first page.
     * Parsing it later, inside the query call, would throw a 500 at a caller
     * who hand-edited a query string.
     */
    private static Cursor decodeCursor(String cursor) {
        Cursor decoded = Cursor.decode(cursor);
        if (decoded == null) {
            return null;
        }
        try {
            Instant.parse(decoded.sortKey());
            return decoded;
        } catch (DateTimeParseException notATimestamp) {
            return null;
        }
    }

    /** One row, with both audit actors already resolved. */
    record Row(long id,
               long userId,
               String userName,
               String module,
               String moduleRole,
               Long grantedBy,
               String grantedByName,
               Instant grantedAt,
               Instant revokedAt,
               Long revokedBy,
               String revokedByName) {

        boolean isLive() {
            return revokedAt == null;
        }
    }

    private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Row(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("user_name"),
                rs.getString("module"),
                rs.getString("module_role"),
                nullableLong(rs, "granted_by"),
                rs.getString("granted_by_name"),
                instant(rs, "granted_at"),
                instant(rs, "revoked_at"),
                nullableLong(rs, "revoked_by"),
                rs.getString("revoked_by_name"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
