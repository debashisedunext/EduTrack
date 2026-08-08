package com.edunext.edutrack.api.feature.auth;

import java.time.Instant;

/**
 * A-020 · exactly the columns authenticating a login needs, and no others.
 *
 * <p>Deliberately not a JPA entity. {@code User} and the rest of the object
 * model are <b>B-005</b> (Ayush), still unstarted; defining one here would
 * collide with that task and make Stream A's login the de-facto owner of a
 * mapping Stream B has to live with. A flat read-only projection over Stream
 * A's own schema needs no such agreement.
 *
 * <p>It is also the better shape for this particular job. Authentication runs
 * on every login attempt including the failing ones, and wants a single narrow
 * row — not an aggregate root with lazy associations that would either
 * N+1-query or over-fetch on the hot path.
 *
 * @param passwordHash Argon2id, written by {@code PasswordHashing.argon2id()}.
 *                     Never leaves this package.
 * @param active         {@code users.is_active}. A deactivated account is
 *                       rejected, and is rejected with the same generic failure
 *                       as a wrong password so that deactivation is not
 *                       observable to an outsider.
 * @param failedAttempts A-021. Consecutive failures since the last success or
 *                       lock. Reset to zero both on a successful login and at
 *                       the moment a lock is applied, so the counter always
 *                       means "failures within the current window".
 * @param lockedUntil    A-021. When the lock lapses, or null if not locked.
 *                       Read but never acted on before the password verifies —
 *                       see {@link AuthenticationService}.
 */
record AuthUserRow(
        long id,
        String username,
        String email,
        String fullName,
        String passwordHash,
        String roleCode,
        int roleId,
        String timezone,
        boolean active,
        boolean mustChangePassword,
        int failedAttempts,
        Instant lockedUntil
) {

    /**
     * A lock in the past is no lock at all — it lapses on its own, with no
     * scheduled job to clear it. Storing the expiry rather than a boolean is
     * what makes that true: nothing has to run for a locked account to become
     * usable again fifteen minutes later.
     */
    boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
