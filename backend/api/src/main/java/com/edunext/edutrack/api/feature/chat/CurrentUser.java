package com.edunext.edutrack.api.feature.chat;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import org.springframework.security.core.Authentication;

/**
 * Who is calling.
 *
 * <p><strong>This is a stopgap and should be deleted.</strong> A-032 puts the
 * real principal in the security context, and A-012 already made
 * {@link DevPrincipal} carry the same shape on purpose, so once A-032 lands
 * every feature should read one type through one accessor and this class has no
 * reason to exist.
 *
 * <p>It lives in the chat feature rather than somewhere shared because a shared
 * home for it would be Stream A's to design, and inventing one here would put a
 * second answer next to the one A-032 is about to give. Chat is simply the
 * first feature that needs the caller's identity — auth's own endpoints know it
 * by other means — so it carries the stopgap until then.
 */
final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * @throws IllegalStateException when nobody is authenticated. That is a
     *         wiring fault, not a client error: the endpoint is behind the
     *         filter chain, so an anonymous caller should have been rejected
     *         before reaching a controller. Failing loudly beats defaulting to
     *         a user id and serving somebody else's conversations.
     */
    static long idOf(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof DevPrincipal dev) {
            return dev.userId();
        }
        throw new IllegalStateException(
                "chat: no identifiable principal (" + (principal == null ? "none" : principal.getClass().getName())
                        + "). A-032 should have populated the security context.");
    }
}
