package com.edunext.edutrack.api.feature.auth;

import java.time.Instant;

/**
 * A-021 · the account is locked, and the caller has <b>already proved they know
 * the password</b>.
 *
 * <p>That precondition is the whole reason this exception may carry detail
 * where {@link InvalidCredentialsException} may not. The contract is explicit:
 * lockout "is reported as {@code account-locked} only <i>after</i> correct
 * credentials, for the same reason" — telling someone who has not authenticated
 * that an account exists and is locked is the same enumeration oracle, just
 * worded differently. By the time this is thrown the caller is not a stranger,
 * so naming the condition and when it lifts helps a real user and tells an
 * attacker nothing they did not already know.
 *
 * @param lockedUntil when the lock lapses, so the response can say how long
 */
class AccountLockedException extends RuntimeException {

    private final transient Instant lockedUntil;

    AccountLockedException(Instant lockedUntil) {
        super("Account locked", null, false, false);
        this.lockedUntil = lockedUntil;
    }

    Instant lockedUntil() {
        return lockedUntil;
    }
}
