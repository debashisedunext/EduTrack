package com.edunext.edutrack.api.security.dev;

import java.util.List;

/**
 * A-012. The shape every fake login takes under the {@code dev-noauth}
 * profile — deliberately the same shape the real JWT principal will carry
 * once A-022 exists ({@code sub}, {@code role}, {@code projects[]},
 * {@code reportees[]}). Because the shapes match, code written against this
 * today — A-034's ScopeResolver, every feature controller B, C and D build —
 * does not change when real authentication replaces the fake one.
 */
public record DevPrincipal(
        Long userId,
        String username,
        String fullName,
        String role,
        List<Long> projectIds,
        List<Long> reporteeIds,
        /**
         * A-110 · which modules this fake principal may reach.
         *
         * <p>Added here as well as to the token for the reason this record
         * exists at all: the two principals are deliberately the same shape,
         * and a claim that lived only on the real chain would make
         * {@code ModuleAccessGuard} behave one way in {@code dev-noauth} and another
         * in production — which is the behaviour three developers would build
         * against for weeks before anyone saw the difference.
         */
        List<String> modules
) {
    /**
     * A-110 · the pre-modules shape, for callers that say nothing about
     * entitlement. Empty rather than both modules, so the neutral default is
     * the refusing one — {@code DevNoAuthProperties} is where dev's permissive
     * default lives, and it is permissive on purpose and in one place.
     */
    public DevPrincipal(Long userId, String username, String fullName, String role,
                        List<Long> projectIds, List<Long> reporteeIds) {
        this(userId, username, fullName, role, projectIds, reporteeIds, List.of());
    }
}
