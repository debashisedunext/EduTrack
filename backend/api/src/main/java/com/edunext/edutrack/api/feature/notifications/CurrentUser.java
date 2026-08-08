package com.edunext.edutrack.api.feature.notifications;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import org.springframework.security.core.Authentication;

/**
 * Who is calling.
 *
 * <p><strong>A duplicate of {@code feature.chat.CurrentUser}, on purpose.</strong>
 * That class explains why it does not live somewhere shared: a shared home for
 * the caller's identity is Stream A's to design in A-032, and inventing one
 * here would put a second answer next to the one A-032 is about to give.
 * Copying ten lines into the second feature that needs it keeps that decision
 * open; extracting a helper would quietly close it.
 *
 * <p>Both copies are deleted when A-032 lands.
 */
final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * @throws IllegalStateException when nobody is authenticated. That is a
     *         wiring fault, not a client error: the endpoint sits behind the
     *         filter chain, so an anonymous caller should never reach a
     *         controller. Failing loudly beats defaulting to a user id and
     *         serving somebody else's bell.
     */
    static long idOf(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof DevPrincipal dev) {
            return dev.userId();
        }
        throw new IllegalStateException(
                "notifications: no identifiable principal ("
                        + (principal == null ? "none" : principal.getClass().getName())
                        + "). A-032 should have populated the security context.");
    }
}
