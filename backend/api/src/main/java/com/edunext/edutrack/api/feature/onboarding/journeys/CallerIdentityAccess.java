package com.edunext.edutrack.api.feature.onboarding.journeys;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.springframework.security.core.Authentication;

/**
 * C-102 · resolves the numeric actor id these controllers stamp onto
 * {@code createdBy} / {@code publishedBy} / the revision editor.
 *
 * <p>Every route in this package sits behind {@code SecurityConfig}'s
 * blanket {@code authenticated()} (see {@code ObJourneyTemplateController}'s
 * class javadoc for why nothing stronger exists yet), so a caller reaching a
 * handler here has already proven a live token. {@link
 * IllegalStateException} rather than a silent {@code null} or a made-up
 * system id is deliberate: {@code CallerIdentity.of} returning empty at this
 * point means the security chain accepted a token this class cannot read,
 * which is a bug worth a loud 500, not a ticket quietly attributed to
 * nobody.
 */
final class CallerIdentityAccess {

    private CallerIdentityAccess() {
    }

    static long requireUserId(Authentication caller) {
        return CallerIdentity.of(caller)
                .map(CallerIdentity::userId)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated onboarding-journeys route reached with no resolvable caller identity"));
    }
}
