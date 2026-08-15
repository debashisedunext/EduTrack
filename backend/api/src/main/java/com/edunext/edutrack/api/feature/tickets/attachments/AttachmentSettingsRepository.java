package com.edunext.edutrack.api.feature.tickets.attachments;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * C-027 · the single {@code attachment_settings} row.
 *
 * <p>{@link JdbcClient} rather than a JPA entity, following
 * {@code ProjectSettingsRepository} beside it in {@code feature/masters}. There
 * is one row with a fixed primary key and no relationships; an entity would buy
 * a persistence context, dirty checking and a second-level cache for a table
 * that is read by primary key and written whole.
 *
 * <p><b>Named for the feature, not for the table</b>, for the reason B-019's
 * repository gives at length: Spring derives a bean name from the simple class
 * name, and a collision with a {@code domain} repository takes out every
 * {@code @SpringBootTest} in the module with a message naming neither.
 *
 * <p>No caching, deliberately. The read is a primary-key lookup of one row
 * against a table that holds exactly one — cheaper than the cache lookup that
 * would front it in most deployments — and a cache here would have to be
 * invalidated by {@link #replace}, which is the step that gets forgotten and
 * whose symptom is a settings screen that saves successfully and changes
 * nothing. If the upload path's extra query ever shows up in a profile, the
 * place to fix it is one {@code @Cacheable} plus one {@code @CacheEvict}, and
 * both belong in the same commit.
 */
@Repository
class AttachmentSettingsRepository {

    /**
     * The row's identity, fixed. The table's {@code CHECK (id = 1)} means this
     * cannot drift into meaning something else — there is no second
     * configuration to select between, and never will be without a migration
     * that says so.
     */
    private static final int SINGLETON_ID = 1;

    private static final String LOAD = """
            SELECT max_file_bytes, max_ticket_bytes, max_files, updated_at, updated_by
              FROM attachment_settings
             WHERE id = ?
            """;

    /**
     * An UPSERT rather than an UPDATE.
     *
     * <p>The row is seeded by the migration, so the insert half should never
     * run. It is here because the alternative — an {@code UPDATE} that matches
     * nothing — reports success with zero rows affected, and the caller would
     * have to check the count to tell "saved" from "there was nothing to save".
     * A settings write that silently does nothing is the failure this feature
     * can least afford, because the next thing the administrator does is trust
     * the number on the screen.
     */
    private static final String REPLACE = """
            INSERT INTO attachment_settings (id, max_file_bytes, max_ticket_bytes, max_files, updated_by)
                 VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE max_file_bytes   = VALUES(max_file_bytes),
                                    max_ticket_bytes = VALUES(max_ticket_bytes),
                                    max_files        = VALUES(max_files),
                                    updated_by       = VALUES(updated_by)
            """;

    private final JdbcClient jdbc;

    AttachmentSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The configured caps, or empty when the row is absent.
     *
     * <p>Absent is not the ordinary state — the migration seeds it — but it is
     * reachable by hand and the caller falls back rather than refusing every
     * upload. See {@link AttachmentSettingsService#effective()}.
     *
     * <p>The values are re-validated through {@link AttachmentLimits#of} on the
     * way out even though the table's {@code CHECK} constraints already hold
     * them. The constraints are the database's guarantee; this is the
     * application's, and they are checked in the one place every read passes
     * through so that a row written by a migration, a fixture or a DBA cannot
     * put the enforcement path into a state the {@code PUT} would have refused.
     */
    Optional<Stored> load() {
        return jdbc.sql(LOAD)
                .param(SINGLETON_ID)
                .query((rs, row) -> new Stored(
                        AttachmentLimits.of(
                                rs.getLong("max_file_bytes"),
                                rs.getLong("max_ticket_bytes"),
                                rs.getInt("max_files")),
                        rs.getTimestamp("updated_at").toInstant(),
                        (Long) rs.getObject("updated_by")))
                .optional();
    }

    /** Write the caps whole. {@code updated_at} is the column's own default. */
    void replace(AttachmentLimits limits, Long updatedBy) {
        jdbc.sql(REPLACE)
                .params(SINGLETON_ID, limits.maxFileBytes(), limits.maxTicketBytes(), limits.maxFiles(), updatedBy)
                .update();
    }

    /**
     * @param updatedBy {@code resources.id}, or null for the seeded row — no
     *                  administrator wrote that one, and attributing it to
     *                  whoever happens to be first would be a small lie in an
     *                  audit-shaped field
     */
    record Stored(AttachmentLimits limits, Instant updatedAt, Long updatedBy) {
    }
}
