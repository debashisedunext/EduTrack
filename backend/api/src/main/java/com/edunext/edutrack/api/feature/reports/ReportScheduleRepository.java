package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * A-065 · the two schedule tables, and the one read the whole security design
 * rests on.
 *
 * @see #callerFor(long) the re-resolution, and why nothing is frozen
 */
@Repository
class ReportScheduleRepository {

    private final JdbcClient jdbc;

    ReportScheduleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One schedule row as stored.
     *
     * @param recipientsJson raw, because it goes back out as JSON and parsing
     *                       it to a list only to re-serialise it would be two
     *                       chances to change it
     */
    record Row(long id, String reportKey, String cadence, String format,
               String recipientsJson, String parametersJson,
               long createdBy, boolean active, Instant nextRunAt, Instant lastRunAt) {
    }

    record RunRow(long id, long scheduleId, Instant runAt, LocalDate periodFrom, LocalDate periodTo,
                  String status, Integer rowCount, String appliedScope,
                  String storageKey, String fileName, String errorText) {
    }

    // ── the schedule itself ──────────────────────────────────────────────────

    long create(String reportKey, ReportCadence cadence, String format,
                String recipientsJson, String parametersJson,
                long createdBy, Instant nextRunAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO report_schedules
                            (report_key, cadence, format, recipients, parameters, created_by, next_run_at)
                        VALUES (:reportKey, :cadence, :format, :recipients, :parameters, :createdBy, :nextRunAt)
                        """)
                .param("reportKey", reportKey)
                .param("cadence", cadence.name())
                .param("format", format)
                .param("recipients", recipientsJson)
                .param("parameters", parametersJson)
                .param("createdBy", createdBy)
                .param("nextRunAt", nextRunAt)
                .update(keys);
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("report_schedules insert returned no generated key");
        }
        return id.longValue();
    }

    Optional<Row> findById(long id) {
        return jdbc.sql(SELECT_SCHEDULE + " WHERE s.id = :id")
                .param("id", id)
                .query(ReportScheduleRepository::schedule)
                .optional();
    }

    /**
     * The owner's own list.
     *
     * <p>Scoped by {@code created_by} and not by role: a schedule is a personal
     * standing instruction, and an Admin browsing everybody's would be a
     * different screen with a different argument. Cancelled ones are included —
     * "why did this stop arriving" is a question the list has to be able to
     * answer, and a row that vanishes on cancel answers it with silence.
     */
    List<Row> findByOwner(long userId) {
        return jdbc.sql(SELECT_SCHEDULE + " WHERE s.created_by = :userId ORDER BY s.created_at DESC")
                .param("userId", userId)
                .query(ReportScheduleRepository::schedule)
                .list();
    }

    /**
     * Everything this caller may see: their own, <b>and the ones they are a
     * recipient of</b>.
     *
     * <p>🔴 The second half is the fix for a defect that made the whole feature
     * work only for one person. {@link #findByOwner} was what the list screen
     * and the download both used, so a colleague who was emailed a report
     * received a link to a page that showed them nothing and a download that
     * answered 404. Scheduling a report <em>to your team</em> — which is the
     * reason the feature exists — produced three people staring at a dead link
     * and one person who could actually open it.
     *
     * <p>Matched on the address rather than on a user id because that is what
     * the schedule stores, for the reason its migration gives: deleting a user
     * must not erase the history of who received last quarter's extract.
     * {@code ReportScheduleService} canonicalises each recipient to the user's
     * own {@code users.email} at creation, so this comparison is exact rather
     * than a case-insensitive JSON search — {@code JSON_SEARCH} compares under
     * {@code utf8mb4_bin} and would miss {@code Priya@…} against
     * {@code priya@…}.
     */
    List<Row> findVisibleTo(long userId) {
        return jdbc.sql(SELECT_SCHEDULE + """
                         WHERE s.created_by = :userId
                            OR JSON_CONTAINS(s.recipients,
                                   JSON_QUOTE((SELECT u.email FROM users u WHERE u.id = :userId)))
                         ORDER BY s.created_at DESC
                        """)
                .param("userId", userId)
                .query(ReportScheduleRepository::schedule)
                .list();
    }

    /**
     * Whether this caller may open one schedule's files — owner or recipient.
     *
     * <p>Separate from {@link #findVisibleTo} rather than reusing it, because
     * the download runs on a link that arrived by email and email gets
     * forwarded: this is the check that has to hold for a caller who is not the
     * person the mail was addressed to, and it should read as its own decision
     * rather than as a filter applied to a list.
     */
    boolean isVisibleTo(long scheduleId, long userId) {
        Long found = jdbc.sql("""
                        SELECT s.id FROM report_schedules s
                         WHERE s.id = :id
                           AND (s.created_by = :userId
                                OR JSON_CONTAINS(s.recipients,
                                       JSON_QUOTE((SELECT u.email FROM users u WHERE u.id = :userId))))
                        """)
                .param("id", scheduleId)
                .param("userId", userId)
                .query(Long.class)
                .optional()
                .orElse(null);
        return found != null;
    }

    /**
     * Everything due, oldest first.
     *
     * <p>Ordered by {@code next_run_at} so a backlog after an outage is drained
     * in the order it accumulated — a schedule that has been waiting three days
     * should not be overtaken by one that came due a minute ago.
     */
    List<Row> findDue(Instant now, int limit) {
        return jdbc.sql(SELECT_SCHEDULE + """
                         WHERE s.is_active = 1 AND s.next_run_at <= :now
                         ORDER BY s.next_run_at
                         LIMIT :limit
                        """)
                .param("now", now)
                .param("limit", limit)
                .query(ReportScheduleRepository::schedule)
                .list();
    }

    /** Cancelling, which is a flag rather than a DELETE — see {@link #findByOwner}. */
    int deactivate(long id, long ownerId) {
        return jdbc.sql("UPDATE report_schedules SET is_active = 0 "
                        + "WHERE id = :id AND created_by = :ownerId AND is_active = 1")
                .param("id", id)
                .param("ownerId", ownerId)
                .update();
    }

    /**
     * Move the clock forward after a firing.
     *
     * <p>Called whether the run succeeded or failed, and that is deliberate: a
     * schedule whose report was withdrawn from the catalogue would otherwise
     * stay due for ever and be retried on every sweep, several times a minute,
     * emailing nothing and writing a FAILED row each time.
     */
    void advance(long id, Instant lastRunAt, Instant nextRunAt) {
        jdbc.sql("UPDATE report_schedules SET last_run_at = :lastRunAt, next_run_at = :nextRunAt "
                        + "WHERE id = :id")
                .param("id", id)
                .param("lastRunAt", lastRunAt)
                .param("nextRunAt", nextRunAt)
                .update();
    }

    // ── runs ─────────────────────────────────────────────────────────────────

    long startRun(long scheduleId, Instant runAt, LocalDate from, LocalDate to) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO report_schedule_runs
                            (schedule_id, run_at, period_from, period_to, status)
                        VALUES (:scheduleId, :runAt, :from, :to, 'RUNNING')
                        """)
                .param("scheduleId", scheduleId)
                .param("runAt", runAt)
                .param("from", from)
                .param("to", to)
                .update(keys);
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("report_schedule_runs insert returned no generated key");
        }
        return id.longValue();
    }

    void succeed(long runId, int rowCount, String appliedScope, String storageKey, String fileName) {
        jdbc.sql("""
                        UPDATE report_schedule_runs
                           SET status = 'SUCCEEDED', row_count = :rowCount, applied_scope = :scope,
                               storage_key = :key, file_name = :fileName
                         WHERE id = :id
                        """)
                .param("id", runId)
                .param("rowCount", rowCount)
                .param("scope", appliedScope)
                .param("key", storageKey)
                .param("fileName", fileName)
                .update();
    }

    void fail(long runId, String errorText) {
        jdbc.sql("UPDATE report_schedule_runs SET status = 'FAILED', error_text = :error WHERE id = :id")
                .param("id", runId)
                // The column is TEXT and the message is whatever an exception
                // said. Truncated rather than trusted: a stack-trace-shaped
                // message would otherwise be the whole row.
                .param("error", errorText == null ? "Unknown error" : trim(errorText, 2000))
                .update();
    }

    List<RunRow> recentRuns(long scheduleId, int limit) {
        return jdbc.sql(SELECT_RUN + " WHERE r.schedule_id = :scheduleId ORDER BY r.run_at DESC LIMIT :limit")
                .param("scheduleId", scheduleId)
                .param("limit", limit)
                .query(ReportScheduleRepository::run)
                .list();
    }

    Optional<RunRow> findRun(long scheduleId, long runId) {
        return jdbc.sql(SELECT_RUN + " WHERE r.id = :runId AND r.schedule_id = :scheduleId")
                .param("runId", runId)
                .param("scheduleId", scheduleId)
                .query(ReportScheduleRepository::run)
                .optional();
    }

    // ── the re-resolution ────────────────────────────────────────────────────

    /**
     * 🔴 The owner's <b>current</b> identity, or empty if they can no longer be
     * one — and this is the security design of the whole feature.
     *
     * <p>A report is scoped to whoever runs it (§2, {@link ReportScope}). A
     * schedule runs with nobody logged in, so the tempting design is to store
     * the creator's role and projects on the row and re-apply them. That is
     * wrong in a way that never announces itself: <b>roles change</b>. A PM who
     * scheduled a project health report and was later moved to Developer would
     * go on receiving project-wide figures every Monday, from a row recording
     * what they used to be, and the mail arriving on time is exactly what would
     * stop anybody noticing.
     *
     * <p>So nothing is frozen. This reads the role and the project memberships
     * as they are <em>now</em>, on every run, and hands back the same
     * {@link CallerIdentity} an HTTP request would carry — which is what lets
     * the run go through the identical {@link ReportService#run} the viewer
     * uses, rather than a second path where scope could be forgotten.
     *
     * <p><b>Empty for an inactive user</b>, which is what makes deactivating a
     * leaver stop their schedules. Nothing else has to remember to.
     */
    Optional<CallerIdentity> callerFor(long userId) {
        return jdbc.sql("""
                        SELECT u.id, r.code AS roleCode
                          FROM users u
                          JOIN roles r ON r.id = u.role_id
                         WHERE u.id = :id AND u.is_active = 1
                        """)
                .param("id", userId)
                .query((rs, n) -> new CallerIdentity(
                        rs.getLong("id"),
                        rs.getString("roleCode"),
                        projectsOf(rs.getLong("id"))))
                .optional();
    }

    /**
     * Active memberships only, matching what {@code AccessTokenIssuer} puts in
     * the {@code projects} claim. A membership deactivated on Friday must not
     * still be scoping Monday's email.
     */
    private List<Long> projectsOf(long userId) {
        return jdbc.sql("SELECT project_id FROM project_members "
                        + "WHERE user_id = :id AND is_active = 1 ORDER BY project_id")
                .param("id", userId)
                .query(Long.class)
                .list();
    }

    /** Display name for the list, resolved once rather than joined per row. */
    Optional<String> displayName(long userId) {
        return jdbc.sql("SELECT full_name FROM users WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .optional();
    }

    /**
     * Recipients that do not belong to an active user.
     *
     * <p>The mail carries a link to an authenticated download, so an address
     * with no account receives a standing invitation to a sign-in page it can
     * never get past. Refused at creation, where the person choosing can fix
     * it, rather than discovered every week by somebody who cannot.
     *
     * <p>Compared case-insensitively: addresses are typed by hand and
     * {@code utf8mb4_0900_ai_ci} already compares this way, so the check
     * matches what the {@code users} unique index would.
     */
    List<String> unknownRecipients(List<String> emails) {
        if (emails.isEmpty()) {
            return List.of();
        }
        List<String> known = activeUsersByEmail(emails).stream()
                .map(u -> u.email().toLowerCase(java.util.Locale.ROOT))
                .toList();
        return emails.stream()
                .filter(e -> !known.contains(e.toLowerCase(java.util.Locale.ROOT)))
                .toList();
    }

    /**
     * The active users behind a set of addresses.
     *
     * <p>Used at creation to refuse an unknown recipient, and again on every run
     * — the second call is the one that matters, because it is what stops
     * mailing somebody whose account was deactivated after the schedule was
     * made. Resolving to a user id also lets the outbox consult their
     * notification preferences; an enqueue with a null user id has none to
     * consult and is always sent.
     */
    List<Recipient> activeUsersByEmail(List<String> emails) {
        if (emails == null || emails.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT id, email FROM users WHERE is_active = 1 AND email IN (:emails)")
                .param("emails", emails)
                .query((rs, n) -> new Recipient(rs.getLong("id"), rs.getString("email")))
                .list();
    }

    record Recipient(Long userId, String email) {
    }

    // ── mapping ──────────────────────────────────────────────────────────────

    private static final String SELECT_SCHEDULE = """
            SELECT s.id, s.report_key, s.cadence, s.format, s.recipients, s.parameters,
                   s.created_by, s.is_active, s.next_run_at, s.last_run_at
              FROM report_schedules s
            """;

    private static final String SELECT_RUN = """
            SELECT r.id, r.schedule_id, r.run_at, r.period_from, r.period_to, r.status,
                   r.row_count, r.applied_scope, r.storage_key, r.file_name, r.error_text
              FROM report_schedule_runs r
            """;

    private static Row schedule(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        java.sql.Timestamp last = rs.getTimestamp("last_run_at");
        return new Row(
                rs.getLong("id"),
                rs.getString("report_key"),
                rs.getString("cadence"),
                rs.getString("format"),
                rs.getString("recipients"),
                rs.getString("parameters"),
                rs.getLong("created_by"),
                rs.getBoolean("is_active"),
                rs.getTimestamp("next_run_at").toInstant(),
                last == null ? null : last.toInstant());
    }

    private static RunRow run(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        int rowCount = rs.getInt("row_count");
        // Captured on the line after the read, not inside the constructor call
        // below. wasNull() reports on the *most recent* getter, and the
        // arguments are evaluated left to right — by the time it was reached in
        // there it would be answering about getString("status"), which is NOT
        // NULL, so a run with no row count would come back as 0. A count of
        // zero rows is a real and different answer from "not recorded".
        boolean noRowCount = rs.wasNull();
        return new RunRow(
                rs.getLong("id"),
                rs.getLong("schedule_id"),
                rs.getTimestamp("run_at").toInstant(),
                // getObject(..., LocalDate.class) rather than getDate().toLocalDate():
                // A-067 found four places where the second converts through the
                // JVM default zone, so a date stored as the 3rd read back as the
                // 2nd on a machine east of UTC. Same trap, same fix.
                rs.getObject("period_from", LocalDate.class),
                rs.getObject("period_to", LocalDate.class),
                rs.getString("status"),
                noRowCount ? null : rowCount,
                rs.getString("applied_scope"),
                rs.getString("storage_key"),
                rs.getString("file_name"),
                rs.getString("error_text"));
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
