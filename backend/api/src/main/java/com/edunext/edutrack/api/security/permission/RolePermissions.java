package com.edunext.edutrack.api.security.permission;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A-033 · the §2 role × permission matrix as a compile-time map.
 *
 * <h2>Why a static map exists when the grants are in the database</h2>
 *
 * <p>A real login does not use this class. {@code AuthUserRepository} reads
 * {@code role_permissions} and A-022 puts the result in the token's
 * {@code permissions} claim, so the database stays the authority and S-09 can
 * edit a role's grants without a redeploy.
 *
 * <p>Two callers need the matrix without a database row to read it from:
 *
 * <ul>
 *   <li><b>{@code DevNoAuthFilter}</b> (A-012). Its principal is a
 *       {@code application-local.yml} property, not a user — there is often no
 *       matching row, and the whole point of the profile is to work before auth
 *       does. Before A-033 it granted {@code ROLE_x} and no permissions at all,
 *       which was invisible because nothing asserted a permission. The moment
 *       the first {@code @PreAuthorize} lands, that same gap turns every
 *       {@code dev-noauth} request into a 403 for Streams B, C and D. Hence this
 *       map, and hence it ships in the same commit as the annotations.</li>
 *   <li><b>A-036's permission matrix</b>, which has to enumerate role × route
 *       expectations without standing up six real accounts per assertion.</li>
 * </ul>
 *
 * <p>{@code RolePermissionsTest} parses the seed migration and asserts this map
 * reproduces it grant for grant. A divergence means {@code dev-noauth} grants
 * something production does not, or refuses something it allows — a local
 * environment that disagrees with the deployed one about authorisation is worse
 * than no local environment, because it is trusted.
 *
 * <h2>SUPPORT, not SUPPORT_DESK</h2>
 *
 * <p>{@code V20260806_0900} seeded {@code SUPPORT_DESK} and
 * {@code V20260807_1030} corrected it to {@code SUPPORT}, which is what the
 * contract's {@code RoleCode} enum, the JWT {@code role} claim and
 * {@code workflow_stages.owner_role} all use. That correction migration names
 * A-033 explicitly as one of the three things it was protecting: with the codes
 * diverged, a role check here would silently deny every Support user. The codes
 * below are post-correction.
 */
public final class RolePermissions {

    public static final String ADMIN = "ADMIN";
    public static final String PM = "PM";
    public static final String SUPPORT = "SUPPORT";
    public static final String DEVELOPER = "DEVELOPER";
    public static final String QA = "QA";
    public static final String DEPLOYMENT = "DEPLOYMENT";

    /** The six system roles of blueprint §2, in matrix-column order. */
    public static final Set<String> ROLE_CODES =
            Set.of(ADMIN, PM, SUPPORT, DEVELOPER, QA, DEPLOYMENT);

    /**
     * Spring's convention: {@code hasRole('ADMIN')} tests the authority
     * {@code ROLE_ADMIN}. Kept as a constant so the converter and
     * {@code DevNoAuthFilter} cannot disagree about it by a character.
     */
    public static final String ROLE_AUTHORITY_PREFIX = "ROLE_";

    private static final Map<String, Set<String>> BY_ROLE_CODE = Map.of(
            ADMIN, Set.of(
                    Permissions.RESOURCE_MANAGE,
                    Permissions.PROJECT_MANAGE,
                    Permissions.TICKET_CREATE,
                    Permissions.TICKET_ASSIGN,
                    Permissions.TICKET_HANDOFF,
                    Permissions.TICKET_REWORK,
                    Permissions.TICKET_SKIP_STAGE,
                    Permissions.TICKET_FORCE_MOVE,
                    Permissions.TICKET_VIEW_ALL,
                    Permissions.TICKET_UPDATE_PROGRESS,
                    Permissions.TICKET_CLOSE,
                    Permissions.TICKET_REOPEN,
                    Permissions.HISTORY_VIEW_TEAM,
                    Permissions.REPORTS_VIEW,
                    Permissions.MASTER_WRITE,
                    Permissions.AUDIT_VIEW),

            PM, Set.of(
                    Permissions.PROJECT_MANAGE,
                    Permissions.TICKET_CREATE,
                    Permissions.TICKET_ASSIGN,
                    Permissions.TICKET_HANDOFF,
                    Permissions.TICKET_REWORK,
                    Permissions.TICKET_SKIP_STAGE,
                    Permissions.TICKET_FORCE_MOVE,
                    Permissions.TICKET_VIEW_ALL,
                    Permissions.TICKET_UPDATE_PROGRESS,
                    Permissions.TICKET_CLOSE,
                    Permissions.TICKET_REOPEN,
                    Permissions.HISTORY_VIEW_TEAM,
                    Permissions.REPORTS_VIEW),

            SUPPORT, Set.of(
                    Permissions.TICKET_CREATE,
                    Permissions.TICKET_ASSIGN,
                    Permissions.TICKET_HANDOFF,
                    Permissions.TICKET_REWORK,
                    Permissions.TICKET_VIEW_ALL,
                    Permissions.TICKET_UPDATE_PROGRESS,
                    Permissions.TICKET_CLOSE,
                    Permissions.TICKET_REOPEN,
                    Permissions.HISTORY_VIEW_TEAM,
                    Permissions.REPORTS_VIEW),

            DEVELOPER, Set.of(
                    Permissions.TICKET_CREATE,
                    Permissions.TICKET_HANDOFF,
                    Permissions.TICKET_REWORK,
                    Permissions.TICKET_VIEW_ASSIGNED,
                    Permissions.TICKET_UPDATE_PROGRESS,
                    Permissions.REPORTS_VIEW),

            QA, Set.of(
                    Permissions.TICKET_CREATE,
                    Permissions.TICKET_HANDOFF,
                    Permissions.TICKET_REWORK,
                    Permissions.TICKET_VIEW_ASSIGNED,
                    Permissions.TICKET_UPDATE_PROGRESS,
                    Permissions.REPORTS_VIEW),

            DEPLOYMENT, Set.of(
                    Permissions.TICKET_CREATE,
                    Permissions.TICKET_HANDOFF,
                    Permissions.TICKET_REWORK,
                    Permissions.TICKET_VIEW_ASSIGNED,
                    Permissions.TICKET_UPDATE_PROGRESS,
                    Permissions.REPORTS_VIEW));

    /**
     * The permission codes granted to a role code.
     *
     * @param roleCode case-insensitive; {@code null} and unknown codes answer an
     *                 empty set rather than throwing. A role this build has never
     *                 heard of — one an Admin added through S-09 after this jar
     *                 was compiled — must degrade to "no permissions", not to a
     *                 500 on a request that has already authenticated somebody.
     *                 A-031's {@code LandingRoutes} learned the same lesson from
     *                 {@code Map.of(...).get(null)} throwing on an immutable map.
     */
    public static Set<String> of(String roleCode) {
        if (roleCode == null) {
            return Set.of();
        }
        return BY_ROLE_CODE.getOrDefault(roleCode.trim().toUpperCase(Locale.ROOT), Set.of());
    }

    /** The whole matrix, unmodifiable — for A-036 and the drift test. */
    public static Map<String, Set<String>> matrix() {
        return BY_ROLE_CODE;
    }

    private RolePermissions() {
    }
}
