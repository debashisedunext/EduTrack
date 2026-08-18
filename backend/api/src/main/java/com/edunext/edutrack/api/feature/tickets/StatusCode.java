package com.edunext.edutrack.api.feature.tickets;

/**
 * The contract's {@code StatusCode} enum, first needed by {@code quickUpdateTicket}
 * (C-036) — no route before it has ever written {@code tickets.status}, so no Java
 * type for it existed to reuse.
 *
 * <p>A real enum rather than a validated {@code String}, unlike {@link
 * com.edunext.edutrack.domain.tickets.Ticket#getLevel()}: a level is one of
 * whatever rows the priorities master currently holds, so no fixed Java type
 * could enumerate it. Status is the opposite — the contract fixes the same eight
 * values it has always fixed — so a bad value is refused by Jackson during
 * argument resolution, before {@code @PreAuthorize} even runs, the same shape of
 * early refusal {@code MULTIPART}'s comment in {@code PermissionMatrix} argues
 * for on a different axis.
 */
public enum StatusCode {
    NEW,
    IN_PROGRESS,
    ON_HOLD,
    AWAITING_INFO,
    REWORK,
    RESOLVED,
    CLOSED,
    REOPENED
}
