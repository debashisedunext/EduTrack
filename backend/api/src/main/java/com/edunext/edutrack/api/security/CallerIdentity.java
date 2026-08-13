package com.edunext.edutrack.api.security;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A-034 · who is calling, in one shape, whichever chain authenticated them.
 *
 * <p>There are two principals in this application and there always will be: a
 * {@link JwtAuthenticationToken} from A-032's real chain, and a
 * {@link DevPrincipal} from A-012's {@code dev-noauth} profile. Both carry the
 * same four facts — {@code sub}, {@code role}, {@code projects[]},
 * {@code reportees[]} — because {@code DevPrincipal} was deliberately shaped
 * to match the token A-022 had not yet minted. This class is the one place
 * that reconciliation happens.
 *
 * <h2>Why this is in {@code security/} and not in {@code scope/}</h2>
 *
 * <p>Two hand-copied {@code CurrentUser} classes exist today, in
 * {@code feature/chat} and {@code feature/notifications}. Both understand
 * {@code DevPrincipal} and nothing else, and both say in their own javadoc
 * that a shared home for the caller's identity was Stream A's to design and
 * that they should be deleted when it arrived. This is it. They are Stream D's
 * files, so they are flagged rather than edited — but note that since A-032
 * landed, a real token reaching either of them raises
 * {@code IllegalStateException} and answers 500. Switching them to this class
 * is a two-line change.
 *
 * <h2>Absent, not defaulted</h2>
 *
 * <p>Every unreadable case returns {@link Optional#empty()} — no
 * authentication, an anonymous token, an unrecognised principal, a
 * {@code sub} that is not numeric. It never returns a partially-filled
 * identity, because the only consumer is a row-scope guard and a caller whose
 * id could not be read must be scoped to nothing rather than to user 0.
 * {@link com.edunext.edutrack.api.security.scope.ScopeResolver} turns the
 * empty into deny-all.
 *
 * <p>The role is read from the {@code role} claim rather than derived from the
 * {@code ROLE_x} authority, for the same reason
 * {@link com.edunext.edutrack.api.security.jwt.JwtAuthoritiesConverter} writes
 * that authority from the claim: the claim is the origin, and reading the
 * derived form would let the two disagree if the converter ever changed.
 *
 * @param userId     the numeric user id — {@code sub}, never the username
 * @param roleCode   upper-cased role code, one of {@code RolePermissions.ROLE_CODES}
 * @param projectIds projects this caller belongs to; the PM/Support row scope of §10.2
 */
public record CallerIdentity(long userId, String roleCode, List<Long> projectIds) {

    static final String ROLE_CLAIM = "role";
    static final String PROJECTS_CLAIM = "projects";

    public CallerIdentity {
        projectIds = projectIds == null ? List.of() : List.copyOf(projectIds);
    }

    /**
     * @return the caller, or empty when nobody identifiable is authenticated.
     *         Callers must treat empty as "sees nothing", never as "sees
     *         everything" and never as a reason to skip the check.
     */
    public static Optional<CallerIdentity> of(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return fromToken(jwtAuthentication.getToken());
        }
        if (authentication.getPrincipal() instanceof DevPrincipal dev) {
            return fromDevPrincipal(dev);
        }
        return Optional.empty();
    }

    private static Optional<CallerIdentity> fromToken(Jwt jwt) {
        Long userId = asId(jwt.getSubject());
        String role = normalisedRole(jwt.getClaimAsString(ROLE_CLAIM));
        if (userId == null || role == null) {
            return Optional.empty();
        }
        return Optional.of(new CallerIdentity(userId, role, idList(jwt.getClaim(PROJECTS_CLAIM))));
    }

    private static Optional<CallerIdentity> fromDevPrincipal(DevPrincipal dev) {
        String role = normalisedRole(dev.role());
        if (dev.userId() == null || role == null) {
            return Optional.empty();
        }
        return Optional.of(new CallerIdentity(dev.userId(), role, idList(dev.projectIds())));
    }

    private static String normalisedRole(String role) {
        return role == null || role.isBlank() ? null : role.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * {@code sub} is a string in the JWT spec and holds our numeric user id
     * (A-022). A non-numeric one is not a client error to report — it is a
     * token we signed that says something we do not understand, so the caller
     * becomes unidentifiable and is scoped to nothing.
     */
    private static Long asId(String subject) {
        try {
            return subject == null ? null : Long.valueOf(subject.trim());
        } catch (NumberFormatException notAnId) {
            return null;
        }
    }

    /**
     * Read defensively, exactly as {@code JwtAuthoritiesConverter} reads
     * {@code permissions}: a claim inside our own signature is a reason to
     * expect it well-formed, not a reason for a malformed one to become a 500.
     *
     * <p>JSON integers arrive as {@code Integer} or {@code Long} depending on
     * magnitude, so every element goes through {@link Number}. A list that is
     * present but unreadable yields an <em>empty</em> list, which for a PM is
     * deny-all — the safe direction.
     */
    private static List<Long> idList(Object claim) {
        if (!(claim instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::longValue)
                .distinct()
                .toList();
    }
}
