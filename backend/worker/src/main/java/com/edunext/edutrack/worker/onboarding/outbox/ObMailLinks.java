package com.edunext.edutrack.worker.onboarding.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * B-111 · where an onboarding mail's button goes.
 *
 * <p>One class, because the answer differs by reader and because the routes do
 * not exist yet. Staff open OB-05, the client opens CP-03, and neither route is
 * mounted on {@code develop} today — B-108/B-109 and the portal screens are
 * still ahead. So the shapes below are a convention this file states rather than
 * discovers, and when those screens land the only place that has to agree with
 * them is here.
 *
 * <p>They mirror the API paths that already exist —
 * {@code /onboarding/clients/{obClientId}} in the generated client — the same
 * way {@code /masters/clients/…} mirrors its own. A deep link that ends up
 * wrong lands the reader on the module's home rather than on a 404, because the
 * client id is the last segment and everything before it is a real page.
 *
 * <h2>An explicit link in the payload always wins</h2>
 *
 * <p>Two events cannot be derived from ids at all: a sign-off carries A-121's
 * one-time token and a password reset carries B-126's, and both live only in the
 * payload the enqueuer wrote. So {@code action_url} is checked first, and
 * anything that supplies it is unaffected by every convention above.
 *
 * <h2>Why the client never gets a staff link</h2>
 *
 * <p>A staff URL mailed to a client contact is an invitation to try it, and the
 * only thing standing behind it is A-110's module guard answering correctly for
 * a {@code CLIENT} principal. It should — but a mail should not be the thing
 * testing that. The audience decides the base path, so the case never arises.
 */
@Component
class ObMailLinks {

    /** OB-05, the staff client detail page. */
    private static final String STAFF_CLIENT = "/onboarding/clients/";

    /** OB-03, when there is no client to open. */
    private static final String STAFF_HOME = "/onboarding/clients";

    /** CP-03, the client portal's onboarding home. */
    private static final String PORTAL_HOME = "/portal/onboarding";

    private final String baseUrl;

    ObMailLinks(@Value("${edutrack.app.base-url:http://localhost:5173}") String baseUrl) {
        // Trailing slash stripped once here rather than guarded at every use,
        // exactly as MailContextRepository does it: "…//onboarding" and
        // "…/onboarding" are both produced by the same concatenation and only
        // one of them looks wrong.
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
    }

    /**
     * @param explicitUrl the payload's {@code action_url}, or null
     * @return an absolute URL, never null
     */
    String actionUrl(ObOutboxMessage message, ObMailAudience audience, String explicitUrl) {
        if (explicitUrl != null && !explicitUrl.isBlank()) {
            return explicitUrl.trim();
        }
        if (audience == ObMailAudience.CLIENT) {
            // The portal shows the signed-in client their own onboarding and
            // nothing else, so it needs no id — and an id in a portal URL is a
            // number somebody will change.
            return baseUrl + PORTAL_HOME;
        }
        return message.obClientId() == null
                ? baseUrl + STAFF_HOME
                : baseUrl + STAFF_CLIENT + message.obClientId();
    }
}
