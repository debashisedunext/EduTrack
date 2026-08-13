package com.edunext.edutrack.api.feature.notifications;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.dev.DevPrincipal;
import org.springframework.security.core.Authentication;

/**
 * Who is calling.
 *
 * <p><b>Fixed under A-034 — this used to answer 500 to every real login.</b>
 * It accepted {@link DevPrincipal} and nothing else, which was correct while
 * {@code dev-noauth} was the only thing populating the security context. Since
 * A-032 landed, the real chain supplies a {@code JwtAuthenticationToken} whose
 * principal is a {@code Jwt}, so every notification, preference and push route
 * raised {@link IllegalStateException} for any caller holding a genuine token.
 *
 * <p>Still a deliberate duplicate of {@code feature.chat.CurrentUser}, and both
 * are now one-line adapters over {@link CallerIdentity} — the shared accessor
 * their previous javadoc said Stream A would design. <b>Deleting both and
 * calling it directly at the fifteen call sites is trivial and remains Stream
 * D's call;</b> it was left undone here so the fix stays a diff that can be
 * reviewed in a minute rather than a refactor across five controllers.
 */
final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * @throws IllegalStateException when nobody identifiable is authenticated.
     *         That is a wiring fault, not a client error: the endpoint sits
     *         behind the filter chain, so an anonymous caller should never
     *         reach a controller. Failing loudly beats defaulting to a user id
     *         and serving somebody else's bell.
     */
    static long idOf(Authentication authentication) {
        return CallerIdentity.of(authentication)
                .map(CallerIdentity::userId)
                .orElseThrow(() -> new IllegalStateException(
                        "notifications: no identifiable principal (" + describe(authentication)
                                + "). The filter chain should have populated the security context."));
    }

    private static String describe(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        return principal == null ? "none" : principal.getClass().getName();
    }
}
