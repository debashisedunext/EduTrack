package com.edunext.edutrack.api.feature.auth;

import java.time.Duration;

/**
 * A-076 · raised when {@link LoginRateLimiter} refuses an attempt.
 *
 * <p>Distinct from {@link AccountLockedException} and deliberately so. That one
 * is a statement about <i>the account</i> — which is why A-021 can only report
 * it once the password is known to be correct. This one is a statement about
 * <i>the caller</i>, is keyed on what was submitted rather than what was found,
 * and therefore says nothing about whether the account exists.
 *
 * <p>Carries no stack trace ({@code writableStackTrace = false}): it is control
 * flow on a public endpoint, and filling one in per refused attempt hands an
 * attacker a cheap way to make the server do work.
 */
class TooManyLoginAttemptsException extends RuntimeException {

    private final transient Duration retryAfter;

    TooManyLoginAttemptsException(Duration retryAfter) {
        super("Too many sign-in attempts", null, false, false);
        this.retryAfter = retryAfter;
    }

    Duration retryAfter() {
        return retryAfter;
    }
}
