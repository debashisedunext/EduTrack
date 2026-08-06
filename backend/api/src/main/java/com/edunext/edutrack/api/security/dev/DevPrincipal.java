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
        List<Long> reporteeIds
) {
}
