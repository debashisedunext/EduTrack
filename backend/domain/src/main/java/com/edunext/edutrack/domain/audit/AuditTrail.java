package com.edunext.edutrack.domain.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A-071 · the only way a row reaches {@code audit_logs}.
 *
 * <p>This is layer 1 of the four that make S-16's "never editable" true, and it
 * is the layer expressed in Java: <b>this class has one method and it is called
 * {@code record}</b>. There is no {@code update}, no {@code delete}, no
 * {@code save} taking an id, and no repository handed out that has them either
 * — {@link AuditLogRepository} was narrowed from {@code JpaRepository} to a
 * finder-only interface in the same commit, because inheriting
 * {@code deleteById} and calling the table append-only is a claim contradicted
 * by autocomplete.
 *
 * <h2>REQUIRES_NEW, and why it is not optional</h2>
 *
 * <p>Almost every audit-worthy moment is either inside a transaction that is
 * about to roll back, or inside one that is read-only. A failed login is the
 * clearest: {@code AuthenticationService.authenticate} is
 * {@code @Transactional(readOnly = true)} and ends by throwing, and Spring
 * rolls back on any unchecked throw — so an audit row written on the caller's
 * transaction would be undone precisely for the events most worth keeping. This
 * is the same trap {@code LoginAttemptRecorder} documented for the failure
 * counter, in the same package, and the same answer: a separate bean, a
 * separate transaction, and never a self-call, because {@code @Transactional}
 * is proxy-based and a call from inside this class would silently bypass it.
 *
 * <h2>A failed write is logged, not thrown</h2>
 *
 * <p>Recording is best-effort by construction, and it has to be admitted rather
 * than hidden. {@code AuditInterceptor} runs in {@code afterCompletion} — the
 * response has already been sent — so there is no failure mode where refusing
 * to record could refuse the operation. Given that, propagating would convert a
 * full disk into a 500 on a request that already succeeded, which loses the
 * audit row <em>and</em> misreports the operation. So the row is lost and an
 * ERROR is logged.
 *
 * <p><b>This is the honest weak point of the design and it is stated here
 * rather than discovered.</b> A truly non-repudiable log records inside the
 * business transaction, so an operation that could not be audited did not
 * happen. That means every service in all four streams calling this at its own
 * commit boundary, which is a change in three other developers' directories and
 * a guarantee only as complete as the last person who remembered. The trade
 * taken is coverage over strictness: every mutating route is recorded today
 * without anyone having to remember, and a service that wants the stronger
 * guarantee for a specific operation calls {@code record} directly inside its
 * own transaction — which works, because this bean is not the one that decides
 * propagation for that path. See {@code feature/audit/README.md}.
 */
@Component
public class AuditTrail {

    private static final Logger log = LoggerFactory.getLogger(AuditTrail.class);

    /** {@code user_agent VARCHAR(255)}. Longer strings are trimmed, never rejected. */
    private static final int USER_AGENT_MAX = 255;

    /** {@code action VARCHAR(60)} and {@code entity_type VARCHAR(60)}. */
    private static final int CODE_MAX = 60;

    /** {@code entity_ref VARCHAR(40)} — V20260818_1500. */
    private static final int ENTITY_REF_MAX = 40;

    /** {@code ip_address VARCHAR(45)}, wide enough for a full IPv6 address. */
    private static final int IP_MAX = 45;

    private static final String INSERT = """
            INSERT INTO audit_logs (actor_id, action, entity_type, entity_id,
                                    entity_ref, old_value, new_value, ip_address, user_agent)
            VALUES (:actorId, :action, :entityType, :entityId,
                    :entityRef, :oldValue, :newValue, :ipAddress, :userAgent)
            """;

    private final JdbcClient jdbc;

    public AuditTrail(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Append one entry. The only write this table has.
     *
     * <p>{@code created_at} is not a parameter: the column defaults to
     * {@code CURRENT_TIMESTAMP(6)}, so the timestamp on an audit row is the
     * database's reading rather than a value the caller supplied. A caller able
     * to pass a time is a caller able to file an action in the past, and the
     * viewer sorts on this column.
     *
     * @return true if the row was written; false if it was not and the failure
     *         was swallowed. Callers are free to ignore it — nothing in this
     *         codebase branches on it — but a test can assert it, and a caller
     *         that genuinely must not proceed unaudited can.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean record(AuditEntry entry) {
        try {
            jdbc.sql(INSERT)
                    .param("actorId", entry.actorId())
                    .param("action", clamp(entry.action(), CODE_MAX))
                    .param("entityType", clamp(entry.entityType(), CODE_MAX))
                    .param("entityId", entry.entityId())
                    .param("entityRef", clamp(entry.entityRef(), ENTITY_REF_MAX))
                    .param("oldValue", entry.oldValue())
                    .param("newValue", entry.newValue())
                    .param("ipAddress", clamp(entry.ipAddress(), IP_MAX))
                    .param("userAgent", clamp(entry.userAgent(), USER_AGENT_MAX))
                    .update();
            return true;
        } catch (RuntimeException e) {
            // Deliberately not rethrown — see the class javadoc. Logged at ERROR
            // with the action and actor so the gap is visible in the log even
            // though it is invisible in the table, which is the failure mode
            // that would otherwise read as "nothing happened".
            log.error("audit: failed to record {} by actor {}",
                    entry.action(), entry.actorId(), e);
            return false;
        }
    }

    /**
     * Truncation rather than rejection, on every field that has a limit.
     *
     * <p>A 300-character User-Agent from some scanner is not a reason to lose
     * the record of what it did, and a {@code DataIntegrityViolationException}
     * here would be caught by the block above and lose exactly that. The two
     * 60-character codes are ours and never near the limit; clamping them is
     * cheap insurance against a caller inventing a long action name.
     */
    private static String clamp(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
