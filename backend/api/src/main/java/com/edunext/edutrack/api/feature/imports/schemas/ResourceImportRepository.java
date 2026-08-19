package com.edunext.edutrack.api.feature.imports.schemas;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * B-038 · every statement the resource registration needs, and nothing else.
 *
 * <p>The registration beside this file is meant to read as "a list of columns
 * plus a few short methods" — that is what B-030 said a registration costs, and
 * it is what makes the second one a file rather than a feature. Resources are
 * the awkward case for that claim, because they have no repository the import
 * can borrow: {@code domain.identity.User} is Stream A's entity and carries none
 * of B-011's five S-08 columns, so {@code ResourceWriteService} does its own
 * writing through {@code JdbcClient} rather than through JPA. This class is that
 * same choice made once more, and keeping it here is what stops the SQL from
 * spreading through the declarations next door.
 *
 * <h2>Why not call {@code ResourceWriteService}</h2>
 *
 * <p>It is the obvious reuse and it does not fit, in three ways that each
 * matter. It takes a {@code ResourceWriteRequest} — the S-08 form's shape,
 * complete with the absent-versus-explicitly-null {@code Optional} wrappers that
 * exist because a form can clear a field; an import cannot, and B-035's rule is
 * that a blank cell leaves the stored value alone. It returns the generated
 * password in its result, which is right for one admin creating one person and
 * meaningless for a background job writing five thousand. And it cannot stamp
 * {@code import_batch_id}, so a run written through it would be unreversible —
 * the one thing B-037 built the seventh SPI method for.
 *
 * <p>The client registration made the same call for the same reason: it writes
 * through {@code ClientRepository} directly rather than through the client
 * service.
 *
 * <h2>Transaction boundaries live here, not on the registration</h2>
 *
 * <p>{@code @Transactional} is applied by a Spring proxy, so a registration
 * calling its own annotated method through {@code this} would bypass it and run
 * with no transaction at all. The delete below removes a person's owned rows and
 * then the person; split across separate commits, a failure between them leaves
 * an account stripped of its memberships and still standing, with nothing
 * recording that it happened. Same reasoning and the same placement as the
 * client registration's own query class.
 */
@Repository
class ResourceImportRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    ResourceImportRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------
    // Read — the dry run
    // ------------------------------------------------------------------

    /**
     * The resources these employee codes name, projected onto the schema's own
     * field names.
     *
     * <p><b>One query for the whole file.</b>
     * {@code ImportSchemaDefinition#findExisting} is explicit that asking per row
     * turns a 5,000-row dry run into 5,000 round trips, on the step users repeat
     * most because it is the safe one.
     *
     * <p>The role arrives as its {@code code} rather than its id, because that is
     * what the uploaded cell holds; compared against an integer, every row in
     * every file would report its role as changing. A stored NULL is left out of
     * the map entirely, matching {@code ImportRow}'s rule that "missing" has one
     * representation — so an incoming value against an empty column reads as a
     * change, which it is.
     *
     * @param empCodes normalised (trimmed, upper-cased) by the registration
     * @return the subset that exists, keyed in that same normalised form
     */
    @Transactional(readOnly = true)
    Map<String, Map<String, String>> currentValues(Set<String> empCodes) {
        if (empCodes.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, String>> existing = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT u.emp_code, u.username, u.email, u.full_name, u.mobile,
                               r.code AS role_code, u.department, u.designation, u.location,
                               u.daily_capacity_hrs, u.timezone, u.is_active,
                               u.date_of_joining, u.weekly_off, u.skills
                        FROM users u
                        JOIN roles r ON r.id = u.role_id
                        WHERE u.emp_code IN (:codes)
                        """)
                .param("codes", empCodes)
                .query((rs, n) -> {
                    Map<String, String> values = new LinkedHashMap<>();
                    put(values, "username", rs.getString("username"));
                    put(values, "email", rs.getString("email"));
                    put(values, "fullName", rs.getString("full_name"));
                    put(values, "mobile", rs.getString("mobile"));
                    put(values, "role", rs.getString("role_code"));
                    put(values, "department", rs.getString("department"));
                    put(values, "designation", rs.getString("designation"));
                    put(values, "location", rs.getString("location"));
                    // Trailing zeros stripped: DECIMAL(4,2) reads back as "8.00"
                    // and a spreadsheet holding a capacity of 8 says "8", so
                    // without this the preview reports a change on every row of
                    // a file that changed nothing. Text, because a cell is text.
                    put(values, "dailyCapacityHrs",
                            plainDecimal(rs.getBigDecimal("daily_capacity_hrs")));
                    put(values, "timezone", rs.getString("timezone"));
                    put(values, "status", rs.getBoolean("is_active") ? "ACTIVE" : "INACTIVE");
                    java.sql.Date joined = rs.getDate("date_of_joining");
                    // ISO, because that is what the DATE validator accepts and so
                    // what the uploaded cell has been normalised to by the time
                    // the two are compared.
                    put(values, "dateOfJoining",
                            joined == null ? null : joined.toLocalDate().toString());
                    put(values, "weeklyOff", csvFromJson(rs.getString("weekly_off")));
                    put(values, "skills", csvFromJson(rs.getString("skills")));
                    return existing.put(normalised(rs.getString("emp_code")), values);
                })
                .list();
        return existing;
    }

    /** The role id behind a code, or empty for a code no role carries. */
    @Transactional(readOnly = true)
    Optional<Integer> findRoleId(String roleCode) {
        return jdbc.sql("SELECT id FROM roles WHERE code = ?")
                .param(roleCode)
                .query(Integer.class)
                .optional();
    }

    @Transactional(readOnly = true)
    Optional<Long> findIdByEmpCode(String empCode) {
        return jdbc.sql("SELECT id FROM users WHERE emp_code = ?")
                .param(empCode)
                .query(Long.class)
                .optional();
    }

    // ------------------------------------------------------------------
    // Write — the commit
    // ------------------------------------------------------------------

    /**
     * Inserts one resource, stamping the batch that created it.
     *
     * <p>{@code import_batch_id} is bound here and nowhere else, which is the
     * whole of "stamped on insert only". {@link #update} takes its column list
     * from the caller, and the registration never puts that column in it.
     */
    long insert(Map<String, Object> columns, Long importBatchId) {
        Map<String, Object> row = new LinkedHashMap<>(columns);
        row.put("import_batch_id", importBatchId);

        String names = String.join(", ", row.keySet());
        String placeholders = String.join(", ", row.keySet().stream().map(c -> "?").toList());

        JdbcClient.StatementSpec spec =
                jdbc.sql("INSERT INTO users (" + names + ") VALUES (" + placeholders + ")");
        for (Object value : row.values()) {
            spec = spec.param(value);
        }
        KeyHolder keys = new GeneratedKeyHolder();
        spec.update(keys);

        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("users INSERT returned no generated key");
        }
        return id.longValue();
    }

    /**
     * Sets exactly the columns named, and no others.
     *
     * <p>The same statement shape {@code ResourceWriteRepository.update} uses,
     * for a related reason: a fixed {@code UPDATE} listing every column would
     * write the ones the spreadsheet did not carry, turning "correct six phone
     * numbers" into "erase every field this file has no column for".
     *
     * <p>Keys are constants from the registration, never a user's column heading
     * — the mapping step addresses fields by name and the registration
     * translates those to columns itself. The values are always bound.
     */
    void update(long userId, Map<String, Object> columns) {
        if (columns.isEmpty()) {
            return;
        }
        String assignments =
                String.join(", ", columns.keySet().stream().map(c -> c + " = ?").toList());
        JdbcClient.StatementSpec spec =
                jdbc.sql("UPDATE users SET " + assignments + " WHERE id = ?");
        for (Object value : columns.values()) {
            spec = spec.param(value);
        }
        spec.param(userId).update();
    }

    /** MySQL's {@code JSON} type takes a document as a string parameter; null in, null out. */
    String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "could not serialise " + value + " for a JSON column", e);
        }
    }

    // ------------------------------------------------------------------
    // Reversal — B-037's seventh SPI method, for this registration
    // ------------------------------------------------------------------

    /**
     * Every resource this run created, in insertion order.
     *
     * <p>Reads {@code ix_users_import_batch}, added by this task's migration and
     * the reason that column exists at all.
     */
    @Transactional(readOnly = true)
    List<CreatedResource> createdBy(long batchId) {
        return jdbc.sql("""
                        SELECT id, emp_code, full_name
                        FROM users
                        WHERE import_batch_id = :batchId
                        ORDER BY id
                        """)
                .param("batchId", batchId)
                .query((rs, n) -> new CreatedResource(
                        rs.getLong("id"), rs.getString("emp_code"), rs.getString("full_name")))
                .list();
    }

    /** One resource an import created, as the reversal needs to talk about it. */
    record CreatedResource(long id, String empCode, String fullName) {
    }

    /**
     * How many tickets each of these resources is named on — reported, assigned
     * to, or assigned by.
     *
     * <p><b>One query for the whole batch</b>, on {@code ix_tickets_reported_by},
     * {@code ix_tickets_assignee_status} and {@code ix_tickets_assigned_by}. Asked
     * per resource this is one round trip per row of the reversal, which is the
     * trap {@code findExisting} exists to avoid on the way in.
     *
     * <p>All three columns are RESTRICT, and all three mean the same thing to
     * whoever reads the answer: work has happened against this account since the
     * import, so the account is not the import's to take back. The union is over
     * ticket ids, so a resource who raised a ticket and was then assigned it
     * counts once rather than twice.
     *
     * <p><b>Every ticket, not only the open ones.</b> A closed ticket is history,
     * its reporter is part of that history, and the foreign key does not care
     * about the status either.
     */
    @Transactional(readOnly = true)
    Map<Long, Long> ticketCounts(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        jdbc.sql("""
                        SELECT user_id, COUNT(*) AS c
                        FROM (
                            SELECT t.id, t.reported_by AS user_id FROM tickets t
                            WHERE t.reported_by IN (:ids)
                            UNION
                            SELECT t.id, t.assigned_to FROM tickets t
                            WHERE t.assigned_to IN (:ids)
                            UNION
                            SELECT t.id, t.assigned_by FROM tickets t
                            WHERE t.assigned_by IN (:ids)
                        ) referencing
                        GROUP BY user_id
                        """)
                .param("ids", userIds)
                .query((rs, n) -> counts.put(rs.getLong("user_id"), rs.getLong("c")))
                .list();
        return counts;
    }

    /**
     * How many other resources report to each of these.
     *
     * <p>{@code fk_users_manager} is RESTRICT and self-referential, so a resource
     * somebody was later hung under cannot be deleted — and should not be: the
     * reporting line above a real employee is not collateral of somebody else's
     * bad spreadsheet.
     *
     * <p>Reachable even though the import cannot set a reporting manager. That
     * field is deliberately absent from the schema (see the registration), so the
     * only way in is an Admin editing S-08 after the import — which is exactly
     * the "work that has happened since" this whole check is about.
     */
    @Transactional(readOnly = true)
    Map<Long, Long> subordinateCounts(Collection<Long> userIds) {
        return countBy(userIds, """
                SELECT reporting_manager_id AS user_id, COUNT(*) AS c
                FROM users
                WHERE reporting_manager_id IN (:ids)
                GROUP BY reporting_manager_id
                """);
    }

    /**
     * How many projects each of these resources manages.
     *
     * <p>{@code fk_projects_manager} is RESTRICT. A project without its manager
     * is not a state the master should be able to reach by way of undoing an
     * import.
     */
    @Transactional(readOnly = true)
    Map<Long, Long> managedProjectCounts(Collection<Long> userIds) {
        return countBy(userIds, """
                SELECT manager_id AS user_id, COUNT(*) AS c
                FROM projects
                WHERE manager_id IN (:ids)
                GROUP BY manager_id
                """);
    }

    private Map<Long, Long> countBy(Collection<Long> userIds, String sql) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        jdbc.sql(sql)
                .param("ids", userIds)
                .query((rs, n) -> counts.put(rs.getLong("user_id"), rs.getLong("c")))
                .list();
        return counts;
    }

    /**
     * <b>One resource and the rows that exist only because the account does,
     * removed together in one transaction.</b>
     *
     * <p>The line this draws is the one the client registration drew between a
     * contact and a ticket, and it is the whole of what a reversal decides. A
     * resource is referenced from around forty foreign keys; the question asked
     * of each is <em>does this row mean anything without the account?</em>
     *
     * <ul>
     *   <li><b>Goes with them.</b> A project membership, a password-reset token,
     *       a password-history entry, a TOTP recovery code, a leave record, a row
     *       of the pre-aggregated per-resource summary. None of these is a fact
     *       about anybody but this person, and none outlives them in any useful
     *       sense. MySQL already takes {@code user_roles}, {@code notifications},
     *       {@code notification_preferences}, {@code push_subscriptions} and
     *       {@code chat_participants} on its own — those foreign keys are ON
     *       DELETE CASCADE, so they are deliberately absent below rather than
     *       forgotten.
     *   <li><b>Keeps them.</b> A ticket, a history entry, an effort log, a
     *       comment, an attachment, a project they manage, somebody who reports
     *       to them. Every one is independent work by or about other people, and
     *       the two ways to make the delete succeed are destroying it or
     *       destroying its author. The row is retained and reported by name.
     * </ul>
     *
     * <p><b>The second list is not enumerated anywhere, and that is the
     * design.</b> Those constraints are RESTRICT, so the database refuses the
     * delete and the registration catches it. Pre-checking forty foreign keys
     * would be a list that goes stale the first time another stream adds one, and
     * a stale list fails silently in the worst possible direction: the missing
     * check reads as "nothing references them".
     *
     * <p><b>One resource per call, by design.</b> The reversal walks its set
     * calling this once per row, so a resource that cannot be removed costs that
     * resource and not the set — the same shape {@code ImportCommitRunner} uses
     * on the way in.
     */
    @Transactional
    void deleteResourceAndOwnedRows(long userId) {
        for (String owned : OWNED_BY_THE_ACCOUNT) {
            jdbc.sql("DELETE FROM " + owned + " WHERE user_id = :userId")
                    .param("userId", userId)
                    .update();
        }
        jdbc.sql("DELETE FROM users WHERE id = :userId")
                .param("userId", userId)
                .update();
    }

    /**
     * Tables whose {@code user_id} is RESTRICT and whose rows mean nothing
     * without the account.
     *
     * <p>Constants, never caller input — see {@link #deleteResourceAndOwnedRows}
     * for what earns a place here and what deliberately does not. The ON DELETE
     * CASCADE tables are absent because MySQL removes them itself; listing them
     * anyway would suggest this is an exhaustive account of what a delete
     * removes, which it is not and must not be read as.
     */
    private static final List<String> OWNED_BY_THE_ACCOUNT = List.of(
            "project_members",
            "password_reset_tokens",
            "password_history",
            "totp_recovery_codes",
            "resource_leaves",
            "resource_daily_stats");

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String normalised(String empCode) {
        // Back to the engine's normalised form: MySQL matched these
        // case-insensitively through the ci collation and returned them in
        // whatever case they were stored in, which will not always be the case
        // the file used.
        return empCode == null ? null : empCode.trim().toUpperCase(Locale.ROOT);
    }

    private static void put(Map<String, String> values, String field, String value) {
        if (value != null && !value.isBlank()) {
            values.put(field, value);
        }
    }

    private static String plainDecimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    /**
     * A stored JSON array as the comma-separated list the spreadsheet column
     * holds, so the preview compares like with like.
     *
     * <p>An unreadable document reads as absent rather than throwing. The value
     * is only ever used to say "this field would change", and failing a whole dry
     * run over one malformed row of somebody else's data would be the wrong
     * trade. Both columns carry a {@code JSON_SCHEMA_VALID} CHECK, so this is the
     * branch that should never fire.
     */
    private String csvFromJson(String document) {
        if (document == null || document.isBlank()) {
            return null;
        }
        try {
            List<?> items = json.readValue(document, List.class);
            return items.isEmpty() ? null
                    : String.join(", ", items.stream().map(String::valueOf).toList());
        } catch (JsonProcessingException unreadable) {
            return null;
        }
    }
}
