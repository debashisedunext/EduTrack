package com.edunext.edutrack.api.feature.portal;

import java.time.Duration;

/** C-121 · {@code TooManyLoginAttemptsException}'s shape, for the portal. */
class PortalTooManyLoginAttemptsException extends RuntimeException {

    private final transient Duration retryAfter;

    PortalTooManyLoginAttemptsException(Duration retryAfter) {
        super("too many portal login attempts", null, false, false);
        this.retryAfter = retryAfter;
    }

    Duration retryAfter() {
        return retryAfter;
    }
}
