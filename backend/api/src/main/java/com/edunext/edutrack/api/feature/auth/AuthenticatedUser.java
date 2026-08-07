package com.edunext.edutrack.api.feature.auth;

import java.util.List;

/**
 * A-020 · who the caller turned out to be, once the password verified.
 *
 * <p>This is the service layer's result — the boundary between "is this person
 * who they say they are" (A-020) and everything built on top of that answer.
 * It carries precisely the §10.1 access-token claim set — {@code sub},
 * {@code role}, {@code permissions[]}, {@code projects[]}, {@code reportees[]} —
 * so that <b>A-022 mints a JWT from this object without re-querying</b>, and
 * A-034's ScopeResolver receives the same shape whether the principal came from
 * a real login or from A-012's {@code dev-noauth} fake.
 *
 * <p>Note what is absent: the password hash. It is read by
 * {@link AuthenticationService}, compared, and never propagated.
 */
record AuthenticatedUser(
        long id,
        String username,
        String email,
        String fullName,
        String roleCode,
        String timezone,
        boolean mustChangePassword,
        List<String> permissions,
        List<Long> projectIds,
        List<Long> reporteeIds
) {
    AuthenticatedUser {
        permissions = List.copyOf(permissions);
        projectIds = List.copyOf(projectIds);
        reporteeIds = List.copyOf(reporteeIds);
    }
}
