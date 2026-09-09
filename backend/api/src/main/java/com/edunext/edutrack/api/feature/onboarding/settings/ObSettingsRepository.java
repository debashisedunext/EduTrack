package com.edunext.edutrack.api.feature.onboarding.settings;

import com.edunext.edutrack.domain.onboarding.ObEscalationLevel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * B-113 · {@code ob_settings} and {@code ob_escalation_rungs}.
 *
 * <p>Plain SQL through {@link JdbcClient} on {@code PriorityUsageRepository}'s
 * reasoning: this is a singleton row plus a three-row child table read as one
 * object for one screen, which is not what a {@code JpaRepository} is for.
 */
@Repository
class ObSettingsRepository {

    private static final String SELECT_SETTINGS = """
            SELECT s.amber_threshold_percent,
                   s.scanner_interval_minutes,
                   s.updated_by,
                   u.full_name AS updated_by_name,
                   s.updated_at
              FROM ob_settings s
              LEFT JOIN users u ON u.id = s.updated_by
             WHERE s.id = 1
            """;

    private static final String SELECT_RUNGS = """
            SELECT level, after_working_hours, recipient
              FROM ob_escalation_rungs
             ORDER BY after_working_hours ASC, level ASC
            """;

    /**
     * <p>{@code UPDATE} rather than an upsert. The row is seeded by the
     * migration and {@code ck_ob_settings_singleton} pins it at id 1, so a
     * missing row is a broken database rather than a state to recover from —
     * and an {@code INSERT ... ON DUPLICATE KEY} here would quietly re-create
     * it with defaults, turning "somebody dropped the row" into "everyone's
     * thresholds silently reset".
     */
    private static final String UPDATE_SETTINGS = """
            UPDATE ob_settings
               SET amber_threshold_percent  = ?,
                   scanner_interval_minutes = ?,
                   updated_by               = ?
             WHERE id = 1
            """;

    /**
     * One statement per rung, keyed on the level.
     *
     * <p>Not a delete-then-insert. The three levels are fixed and always
     * present, so replacing the set would open a window in which the ladder is
     * empty — and a scanner sweeping in that window would find no rung to fire
     * and would record nothing rather than failing loudly.
     */
    private static final String UPDATE_RUNG = """
            UPDATE ob_escalation_rungs
               SET after_working_hours = ?,
                   recipient           = ?
             WHERE level = ?
            """;

    private final JdbcClient jdbc;

    ObSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    ObSettingsDtos.Settings load() {
        List<ObSettingsDtos.Rung> ladder = jdbc.sql(SELECT_RUNGS)
                .query((rs, row) -> new ObSettingsDtos.Rung(
                        ObEscalationLevel.valueOf(rs.getString("level")),
                        rs.getInt("after_working_hours"),
                        ObSettingsDtos.Recipient.valueOf(rs.getString("recipient"))))
                .list();

        return jdbc.sql(SELECT_SETTINGS)
                .query((rs, row) -> {
                    long updatedBy = rs.getLong("updated_by");
                    ObSettingsDtos.UserRef by = rs.wasNull() ? null
                            : new ObSettingsDtos.UserRef(updatedBy, rs.getString("updated_by_name"));
                    Timestamp updatedAt = rs.getTimestamp("updated_at");
                    return new ObSettingsDtos.Settings(
                            rs.getInt("amber_threshold_percent"),
                            rs.getInt("scanner_interval_minutes"),
                            ladder,
                            by,
                            updatedAt == null ? null : updatedAt.toInstant());
                })
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "ob_settings row 1 is missing — the migration seeds it and "
                                + "ck_ob_settings_singleton pins it, so this means the row was deleted"));
    }

    void save(ObSettingsDtos.WriteRequest request, Long actorUserId) {
        jdbc.sql(UPDATE_SETTINGS)
                .param(request.amberThresholdPercent())
                .param(request.scannerIntervalMinutes())
                .param(actorUserId)
                .update();

        // Sorted so the write order is the ladder's order regardless of how the
        // client sent it. Not load-bearing — each statement is keyed on its own
        // level — but it makes the statement log read as the ladder rather than
        // as whatever order a form serialised its rows in.
        request.ladder().stream()
                .sorted(Comparator.comparing(ObSettingsDtos.Rung::level))
                .forEach(rung -> jdbc.sql(UPDATE_RUNG)
                        .param(rung.afterWorkingHours())
                        .param(rung.recipient().name())
                        .param(rung.level().name())
                        .update());
    }

    /** @return the settings row's own last-modified stamp, for the ETag. */
    Instant lastModified() {
        return load().updatedAt();
    }
}
