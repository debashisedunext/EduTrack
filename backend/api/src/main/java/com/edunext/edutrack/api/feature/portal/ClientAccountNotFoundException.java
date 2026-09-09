package com.edunext.edutrack.api.feature.portal;

/**
 * B-126 · 404 — no such onboarding client, or one with no portal login.
 *
 * <p><b>One exception for two facts, deliberately.</b> The panel's caller is
 * staff who can already see the client, so this is not the existence-leak rule
 * A-112 enforces elsewhere; it is simpler than that. "This client has no
 * account" and "there is no such client" both mean the operation has nothing to
 * act on, and the screen's response to either is the same: offer to create one.
 * Two statuses would be two branches on a screen that has one.
 */
class ClientAccountNotFoundException extends RuntimeException {

    ClientAccountNotFoundException(long obClientId) {
        super("no portal account for onboarding client " + obClientId);
    }
}
