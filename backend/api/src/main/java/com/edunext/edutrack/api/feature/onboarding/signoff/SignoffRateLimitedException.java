package com.edunext.edutrack.api.feature.onboarding.signoff;

import java.time.Duration;

/**
 * A-120 · the surface refused this caller for now rather than for ever.
 *
 * <p>Distinct from {@link InvalidSignoffTokenException} because it says
 * something different and discloses nothing extra: a 429 tells the caller they
 * are going too fast, which is true whether or not the token was ever valid.
 * It is raised <b>before</b> the token is looked at, so it cannot leak whether
 * one exists.
 */
public class SignoffRateLimitedException extends RuntimeException {

    private final Duration retryAfter;

    public SignoffRateLimitedException(Duration retryAfter) {
        super("Too many sign-off attempts");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
