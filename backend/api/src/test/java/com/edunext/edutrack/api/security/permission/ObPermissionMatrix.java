package com.edunext.edutrack.api.security.permission;

import com.edunext.edutrack.api.security.module.ObModuleRoleRules;

import java.util.Map;
import java.util.Set;

/**
 * A-114, completed by A-122 · the onboarding module's five staff roles against every onboarding
 * route, taken from Onboarding-Module-Plan.md §3.
 *
 * <h2>A second matrix, not more rows in the first one</h2>
 *
 * <p>{@link PermissionMatrix} speaks blueprint §2's six <em>platform</em> roles
 * — ADMIN, PM, DEVELOPER and so on — which is what {@code @PreAuthorize} and
 * {@code RolePermissions} are written in. The onboarding module has its own
 * vocabulary in {@code user_module_access.module_role}, and the two are
 * orthogonal: a platform ADMIN may hold {@code OB_VIEWER} or no onboarding
 * standing at all. Folding them into one table would need a nullable dimension
 * and a predicate on every lookup, and the first person to forget the predicate
 * gets a matrix that answers confidently about the wrong vocabulary.
 *
 * <p>The sixth role in §3's table — <b>Client</b> — is deliberately absent. It
 * is an external principal on {@code /api/v1/portal/**} with its own token
 * shape ({@code ClientPrincipal}, A-125) and its own resolver (A-126), and it
 * reaches none of the routes below. Encoding it here would assert a rule about
 * a caller that cannot arrive.
 *
 * <h2>What is declared here is the plan, not the code</h2>
 *
 * <p>This is what A-114 had to state rather than let somebody discover:
 * <b>of the thirty-eight rules declared here, the application applied four.</b>
 * {@code POST /journey-steps/{stepId}/skip} checked the module role through
 * {@code CallerIdentityAccess.onboardingModuleRole} and refused with
 * {@code NotAnOnboardingModeratorException}, A-117's three module-access routes
 * checked it in their own controller, the dashboard and report routes read it
 * to <em>scope</em> what they return — a different thing — and the remaining
 * thirty-four did not consult it at all.
 *
 * <p><b>A-122 emptied that set, and this class stopped holding the rules.</b>
 * They live in {@code ObModuleRoleRules}, in main, where
 * {@code ObModuleRoleFilter} applies them on every request. A rule set that
 * only tests could see described the application rather than constraining it —
 * the shape {@code ModuleAccessGuard} was in for three weeks before A-111's
 * second half, and the reason A-114 counted the gap rather than leaving it
 * unremarked.
 *
 * <p>What is left here is the delegation. The test that reads it is no longer a
 * second opinion about what the rules are; it checks that the shipped ones
 * cover every route and match §3.
 */
final class ObPermissionMatrix {

    private static final ObModuleRoleRules SHIPPED = new ObModuleRoleRules();

    static final String OB_ADMIN = ObModuleRoleRules.OB_ADMIN;
    static final String OB_MANAGER = ObModuleRoleRules.OB_MANAGER;
    static final String OB_SALES = ObModuleRoleRules.OB_SALES;
    static final String OB_STEP_OWNER = ObModuleRoleRules.OB_STEP_OWNER;
    static final String OB_VIEWER = ObModuleRoleRules.OB_VIEWER;

    /** The five staff roles, as the CHECK constraint spells them. */
    static final Set<String> ALL_ROLES = ObModuleRoleRules.ALL_ROLES;

    /**
     * The rules the application actually applies, keyed as
     * {@code RouteInventory#routeKeys} spells a route.
     */
    static final Map<String, Set<String>> ENTRIES = SHIPPED.asRouteKeys();

    /**
     * <b>Empty, as of A-122.</b>
     *
     * <p>It held thirty-four routes: rules §3 stated and the code did not
     * apply. {@code ObModuleRoleFilter} now applies all of them, so the honest
     * value is no routes at all.
     *
     * <p>Kept rather than deleted, and kept asserted. The set exists to make
     * "declared but not enforced" a number somebody has to change deliberately;
     * deleting it once it reached zero would remove the thing that notices the
     * next rule to arrive without an enforcement point, which is exactly how
     * the first thirty-four accumulated.
     */
    static final Set<String> NOT_YET_ENFORCED = Set.of();

    private ObPermissionMatrix() {
    }
}
