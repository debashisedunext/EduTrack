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
 * @param passwordChangedAt A-028. When the current password was set. UTC, and
 *                       {@code NOT NULL} in the schema — read by
 *                       {@link PasswordPolicy#isExpired} for §10.3's optional
 *                       90-day rule, and ignored entirely while that rule is
 *                       switched off, which is the default.
 * @param totpSecret  A-029. The shared secret, <b>still encrypted</b> — this
 *                    record carries it as stored, and only
 *                    {@code TotpSecretCipher} turns it back into something an
 *                    authenticator would recognise. Null until enrolment starts.
 *                    Note it is populated whether or not 2FA is enabled: a
 *                    secret exists from setup and means nothing until
 *                    {@code totpEnabled} says so.
 * @param totpEnabled A-029. <b>The only field the login path consults.</b> True
 *                    only once the user has echoed a valid code back, so a QR
 *                    that never scanned cannot lock anyone out of the account
 *                    they were trying to protect.
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
        Instant passwordChangedAt,
        String totpSecret,
        boolean totpEnabled,
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
