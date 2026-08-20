package com.edunext.edutrack.api.feature.auth;

import java.time.Duration;

/**
 * A-074 · raised when {@link PasswordChangeRateLimiter} refuses an attempt at
 * {@code PATCH /me/password}.
 *
 * <p>Distinct from {@link TooManyLoginAttemptsException}, which is about a
 * caller nobody has identified yet. This one is about a <i>known</i> user — the
 * caller has already presented a token we issued — so it is a statement about
 * that account's budget for wrong {@code currentPassword} guesses and nothing
 * else. Keeping the two apart matters in logs: a spike here means somebody is
 * escalating a session they already hold, which is a materially worse signal
 * than a spike on login and should never be aggregated with it.
 *
 * <p>Carries no stack trace ({@code writableStackTrace = false}), like the other
 * two throttle exceptions: it is control flow rather than a fault.
 */
class TooManyPasswordChangeAttemptsException extends RuntimeException {

    private final transient Duration retryAfter;

    TooManyPasswordChangeAttemptsException(Duration retryAfter) {
        super("Too many password change attempts", null, false, false);
        this.retryAfter = retryAfter;
    }

    Duration retryAfter() {
        return retryAfter;
    }
}
