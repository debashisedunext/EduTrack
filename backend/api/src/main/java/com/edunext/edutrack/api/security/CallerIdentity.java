package com.edunext.edutrack.api.security;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * @param modules    A-110 · module codes this caller may reach. Read by
 *                   {@code ModuleAccessGuard} before RolesGuard on every
 *                   {@code /api/v1/onboarding/**} route (onboarding plan §2.1).
 *                   <b>Empty means no modules, never all of them</b> — see
 *                   {@link #hasModule}.
 * @param moduleRoles A-112 · the caller's role <em>within</em> each module they
 *                   hold, keyed by module code — {@code {"ONBOARDING": "OB_SALES"}}.
 *                   Distinct from {@code roleCode}, which is the ticketing-era
 *                   role and says nothing about onboarding: a user is
 *                   {@code SUPPORT} there and {@code OB_SALES} here, and
 *                   {@code OnboardingScopeResolver} switches on the latter.
 *                   <b>Absent means no role in that module, never a
 *                   privileged one</b> — see {@link #moduleRole}.
 */
public record CallerIdentity(long userId, String roleCode, List<Long> projectIds,
                             List<String> modules, Map<String, String> moduleRoles) {

    static final String ROLE_CLAIM = "role";
    static final String PROJECTS_CLAIM = "projects";
    /** A-110. Written unconditionally by {@code AccessTokenIssuer}, so absent means empty. */
    static final String MODULES_CLAIM = "modules";
    /** A-112. Written unconditionally alongside {@link #MODULES_CLAIM}, so absent means empty. */
    static final String MODULE_ROLES_CLAIM = "moduleRoles";

    public CallerIdentity {
        projectIds = projectIds == null ? List.of() : List.copyOf(projectIds);
        // Normalised here rather than in hasModule, because a record that
        // stores what it was given and compares leniently is a record whose
        // answer depends on which constructor was used. `stringList` already
        // trims what it reads off a claim; this covers every other way one of
        // these is built — a test, a scheduled report rebuilding an identity
        // from the database — with the same rule.
        modules = modules == null ? List.of() : modules.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        // Keys upper-cased for the same reason `hasModule` compares
        // case-insensitively — the code is minted from a database column and
        // matched against a Java constant. Values are NOT upper-cased here
        // beyond trimming: `moduleRole` hands the raw grant to a switch that
        // treats anything unrecognised as deny-all, and silently repairing a
        // malformed role would hide the misconfiguration rather than refuse it.
        moduleRoles = moduleRoles == null ? Map.of() : moduleRoles.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> entry.getKey().trim().toUpperCase(Locale.ROOT),
                        entry -> entry.getValue().trim(),
                        (first, second) -> first));
    }

    /**
     * A-112 · the pre-moduleRoles shape. Empty is deny, for the reason the
     * three-argument constructor below gives at length: a caller that says
     * nothing about its role inside a module has no role inside it.
     */
    public CallerIdentity(long userId, String roleCode, List<Long> projectIds, List<String> modules) {
        this(userId, roleCode, projectIds, modules, Map.of());
    }

    /**
     * A-110 · the pre-modules shape, for every caller that has nothing to say
     * about entitlement.
     *
     * <p>Added rather than widening the canonical constructor across 56 call
     * sites in four streams' test files. Two reasons, and the second is the
     * real one:
     *
     * <ul>
     *   <li>Most of those files are Stream B's, C's and D's (TEAM-PLAN §6), and
     *       a claim added by Stream A should not require edits in their tests.
     *   <li><b>Empty is the correct answer for all of them anyway.</b> A test
     *       that says nothing about modules is a test about ticketing, and
     *       ticketing routes are not guarded — while any onboarding route
     *       reached with this identity is refused, which is the direction this
     *       whole feature commits to. Defaulting the other way would have made
     *       every existing test silently entitled.
     * </ul>
     */
    public CallerIdentity(long userId, String roleCode, List<Long> projectIds) {
        this(userId, roleCode, projectIds, List.of(), Map.of());
    }

    /**
     * A-110 · whether this caller may reach {@code module}.
     *
     * <p><b>Empty is deny, exactly as an empty {@code projectIds} is deny for a
     * PM.</b> The class javadoc above already commits to that direction for an
     * unreadable identity; this keeps a readable identity with an unreadable or
     * missing claim on the same side of it. A token we signed before this claim
     * existed therefore reaches no module, which is the answer that cannot leak
     * one.
     *
     * <p>Case-insensitive, because the claim is minted from a database column
     * and compared against a constant in Java — two places a case can drift
     * apart, and neither of them is worth a 404 nobody can explain.
     */
    public boolean hasModule(String module) {
        if (module == null || module.isBlank()) {
            return false;
        }
        return modules.stream().anyMatch(module.trim()::equalsIgnoreCase);
    }

    /**
     * A-112 · this caller's role inside {@code module}, if they have one.
     *
     * <p><b>Empty is deny.</b> {@code OnboardingScopeResolver} turns an empty
     * into its deny-all specification, which is the same direction
     * {@link #hasModule} takes for an absent grant and the same direction the
     * class javadoc takes for an unreadable identity. A token minted before
     * this claim existed therefore has no role in any module — so it is scoped
     * to nothing rather than defaulting into the one role that sees every
     * journey.
     *
     * <p>Deliberately <em>not</em> cross-checked against {@link #modules}. The
     * two claims are minted from the same row of {@code user_module_access} in
     * the same query, so they cannot disagree; and if they ever did,
     * {@code ModuleAccessGuard} already refuses the request on {@link #hasModule}
     * before a resolver is consulted. Re-deciding entitlement here would put
     * the module gate in two places, which is the arrangement that lets one of
     * them be relaxed without the other.
     */
    public Optional<String> moduleRole(String module) {
        if (module == null || module.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(moduleRoles.get(module.trim().toUpperCase(Locale.ROOT)));
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
        return Optional.of(new CallerIdentity(
                userId, role, idList(jwt.getClaim(PROJECTS_CLAIM)),
                stringList(jwt.getClaim(MODULES_CLAIM)), stringMap(jwt.getClaim(MODULE_ROLES_CLAIM))));
    }

    private static Optional<CallerIdentity> fromDevPrincipal(DevPrincipal dev) {
        String role = normalisedRole(dev.role());
        if (dev.userId() == null || role == null) {
            return Optional.empty();
        }
        return Optional.of(new CallerIdentity(
                dev.userId(), role, idList(dev.projectIds()),
                stringList(dev.modules()), stringMap(dev.moduleRoles())));
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
    /**
     * A-110 · the {@code modules} claim, read with {@link #idList}'s caution
     * and none of its numeric assumptions.
     *
     * <p>Blank entries are dropped rather than kept as empty strings, so a
     * claim of {@code ["", "ONBOARDING"]} cannot be read as granting a module
     * whose code is the empty string — which {@code hasModule} already refuses
     * to ask about, but the two guards belong on the same side of that.
     */
    private static List<String> stringList(Object claim) {
        if (!(claim instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    /**
     * A-112 · the {@code moduleRoles} claim, read with {@link #stringList}'s
     * caution. A claim that is present but not an object, or whose entries are
     * not strings, yields an empty map — which is deny for every module, the
     * safe direction.
     */
    private static Map<String, String> stringMap(Object claim) {
        if (!(claim instanceof Map<?, ?> values)) {
            return Map.of();
        }
        return values.entrySet().stream()
                .filter(entry -> entry.getKey() instanceof String && entry.getValue() instanceof String)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> (String) entry.getKey(),
                        entry -> (String) entry.getValue(),
                        (first, second) -> first));
    }

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
