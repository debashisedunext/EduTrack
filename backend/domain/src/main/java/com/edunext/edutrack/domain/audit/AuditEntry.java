package com.edunext.edutrack.domain.audit;

/**
 * A-071 · one thing that happened, on its way to {@code audit_logs}.
 *
 * <p>Separate from {@link AuditLog} rather than reusing it, and the split is
 * the point. {@code AuditLog} is a JPA entity with setters and an id — a shape
 * that can be loaded, changed and written back, which is the operation this
 * table exists to refuse. This record has no id, no setters and is handed to
 * {@link AuditTrail#record} exactly once. A caller holding one has no way to
 * express "and now change it".
 *
 * <h2>{@code actorId} may be null and that is a real value, not a gap</h2>
 *
 * <p>Null means SYSTEM: A-044's nightly chain verifier, D-030's SLA scanner and
 * the mail engine all act with no human behind them, and recording those as
 * user 0 or as the last person to log in would be worse than recording nothing.
 * The viewer renders it as "System".
 *
 * <h2>{@code entityId} and {@code entityRef} are alternatives, not a pair</h2>
 *
 * <p>Most subjects have a numeric id. Tickets do not — they are addressed by
 * code, {@code CRM-26-00347} — so {@link #forRef} sets the reference and leaves
 * the id null. {@code V20260818_1500} carries the reasoning; the constructor
 * below refuses both at once so that a reader of a row never has to work out
 * which one is authoritative.
 *
 * <h2>{@code oldValue} and {@code newValue} are strings, deliberately loosely</h2>
 *
 * <p>The columns are {@code TEXT} and hold whatever the caller can honestly say
 * — a JSON fragment, a single field, or nothing at all. Most rows carry
 * nothing: {@code AuditInterceptor} records that a request happened and cannot
 * see what a service changed underneath it, and reconstructing a before-and-
 * after from the request body would produce a diff that looks authoritative and
 * is a guess. A caller genuinely holding both values passes them; the rest leave
 * them null, and the viewer distinguishes "no detail recorded" from "changed to
 * empty".
 *
 * @param actorId    the user who did it, or null for SYSTEM
 * @param action     the vocabulary term — {@code LOGIN_SUCCESS}, {@code TICKETS_CREATED}
 * @param entityType the module the subject belongs to, or null where there is none
 * @param entityId   the subject's numeric id, or null
 * @param entityRef  the subject's reference where it has no numeric id, or null
 * @param oldValue   what it was, where the caller can say; null otherwise
 * @param newValue   what it became, where the caller can say; null otherwise
 * @param ipAddress  the caller's address as far as it can be established
 * @param userAgent  truncated to the column's 255 by {@link AuditTrail}
 */
public record AuditEntry(
        Long actorId,
        String action,
        String entityType,
        Long entityId,
        String entityRef,
        String oldValue,
        String newValue,
        String ipAddress,
        String userAgent) {

    public AuditEntry {
        if (action == null || action.isBlank()) {
            // An audit row with no verb is one nobody can read and nobody can
            // filter for. Fail at the caller rather than write it.
            throw new IllegalArgumentException("an audit entry must name an action");
        }
        if (entityId != null && entityRef != null) {
            throw new IllegalArgumentException(
                    "an audit entry names its subject by id or by reference, never both");
        }
    }

    /** An actor, a verb, and a subject with a numeric id. */
    public static AuditEntry of(Long actorId, String action, String entityType, Long entityId) {
        return new AuditEntry(actorId, action, entityType, entityId, null, null, null, null, null);
    }

    /** The same, for a subject addressed by reference — every ticket route. */
    public static AuditEntry forRef(Long actorId, String action, String entityType, String entityRef) {
        return new AuditEntry(actorId, action, entityType, null, entityRef, null, null, null, null);
    }

    /** The same, carrying the caller's origin — everything the login path records. */
    public AuditEntry from(String ipAddress, String userAgent) {
        return new AuditEntry(actorId, action, entityType, entityId, entityRef,
                oldValue, newValue, ipAddress, userAgent);
    }

    /** The same, carrying a before and after the caller genuinely holds. */
    public AuditEntry changing(String oldValue, String newValue) {
        return new AuditEntry(actorId, action, entityType, entityId, entityRef,
                oldValue, newValue, ipAddress, userAgent);
    }
}
