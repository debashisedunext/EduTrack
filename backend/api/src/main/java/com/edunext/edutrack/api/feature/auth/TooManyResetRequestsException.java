package com.edunext.edutrack.api.feature.auth;

import java.time.Duration;

/**
 * A-027 · {@code POST /auth/forgot-password} was called more often than
 * {@link PasswordResetRateLimiter} allows.
 *
 * <p>Carries the retry delay because the contract's {@code TooManyRequests}
 * response declares a {@code Retry-After} header, and a 429 without one tells a
 * client to back off without saying how far — which in practice means it retries
 * immediately and stays refused.
 *
 * <p><b>This status does not leak whether the account exists</b>, because the
 * budget is spent before anything is looked up. A known and an unknown address
 * are throttled identically; all a 429 reveals is how often <i>this caller</i>
 * has asked, which they already know.
 */
class TooManyResetRequestsException extends RuntimeException {

    private final transient Duration retryAfter;

    TooManyResetRequestsException(Duration retryAfter) {
        super("Too many password reset requests", null, false, false);
        this.retryAfter = retryAfter;
    }

    Duration retryAfter() {
        return retryAfter;
    }
}
