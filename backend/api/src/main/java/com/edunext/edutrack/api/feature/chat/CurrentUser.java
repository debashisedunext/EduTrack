package com.edunext.edutrack.api.feature.chat;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.dev.DevPrincipal;
import org.springframework.security.core.Authentication;

/**
 * Who is calling.
 *
 * <p><b>Fixed under A-034 — this used to answer 500 to every real login.</b>
 * It accepted {@link DevPrincipal} and nothing else, which was correct while
 * {@code dev-noauth} was the only thing populating the security context. Since
 * A-032 landed, the real chain supplies a
 * {@code JwtAuthenticationToken} whose principal is a {@code Jwt}, so every
 * chat route raised {@link IllegalStateException} for any caller holding a
 * genuine token. Nothing caught it: {@code SecurityChainIT} is the only test
 * that reaches one of these routes with a real token and it asserts the status
 * is not 401 — correct scoping for A-032, and a 500 passes it.
 *
 * <p>The class stays as a one-line adapter rather than being deleted, and the
 * fifteen call sites across chat and notifications are untouched, so the change
 * is a diff Stream D can read in a minute. {@link CallerIdentity} is the shared
 * accessor this class's previous javadoc was waiting for; <b>deleting both
 * copies and calling it directly is now trivial and remains Stream D's call.</b>
 */
final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * @throws IllegalStateException when nobody identifiable is authenticated.
     *         That is a wiring fault, not a client error: the endpoint is
     *         behind the filter chain, so an anonymous caller should have been
     *         rejected before reaching a controller. Failing loudly beats
     *         defaulting to a user id and serving somebody else's
     *         conversations.
     */
    static long idOf(Authentication authentication) {
        return CallerIdentity.of(authentication)
                .map(CallerIdentity::userId)
                .orElseThrow(() -> new IllegalStateException(
                        "chat: no identifiable principal (" + describe(authentication)
                                + "). The filter chain should have populated the security context."));
    }

    private static String describe(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        return principal == null ? "none" : principal.getClass().getName();
    }
}
