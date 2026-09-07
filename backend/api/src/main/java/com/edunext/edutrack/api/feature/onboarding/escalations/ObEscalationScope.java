package com.edunext.edutrack.api.feature.onboarding.escalations;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.module.ModuleAccessGuard;

/**
 * C-115 · A-112's row-scope rule, written a second time for {@code
 * ob_escalations} — {@code ObReportScope}'s exact reasoning one package
 * over: {@code OnboardingScopeResolver} answers a JPA {@code
 * Specification<ObJourney>}, and this list is a plain scoped read no
 * {@code ObJourney} root passes through, so the rule reaches SQL directly
 * instead. See that class's own javadoc for why two expressions of one
 * security rule is the risk, not a convenience.
 *
 * <p><b>Simpler than {@code ObReportScope}'s equivalent</b>, because {@code
 * ob_escalations} already carries {@code ob_client_id} and {@code step_id}
 * on the row — no join through {@code ob_journeys} is needed to reach either
 * predicate.
 */
record ObEscalationScope(String moduleRole, long userId) {

    /** Plan §3's five module roles. Mirrors {@code OnboardingScopeResolver}'s own constants. */
    static final String OB_ADMIN = "OB_ADMIN";
    static final String OB_MANAGER = "OB_MANAGER";
    static final String OB_VIEWER = "OB_VIEWER";
    static final String OB_SALES = "OB_SALES";
    static final String OB_STEP_OWNER = "OB_STEP_OWNER";

    static final String USER_PARAM = "scopeUserId";

    static ObEscalationScope of(CallerIdentity caller) {
        return new ObEscalationScope(
                caller.moduleRole(ModuleAccessGuard.ONBOARDING).orElse(""), caller.userId());
    }

    /** §3's three roles that see every journey, and therefore every escalation. */
    boolean unrestricted() {
        return OB_ADMIN.equals(moduleRole) || OB_MANAGER.equals(moduleRole) || OB_VIEWER.equals(moduleRole);
    }

    /** True for no grant, an unknown role, and {@code TICKETING_MEMBER} — deny by default. */
    boolean deniesEverything() {
        return !unrestricted() && !OB_SALES.equals(moduleRole) && !OB_STEP_OWNER.equals(moduleRole);
    }

    /** The scope as a SQL predicate over the query's own {@code ob_escalations} alias. */
    String predicate(String alias) {
        if (unrestricted()) {
            return "1 = 1";
        }
        if (OB_SALES.equals(moduleRole)) {
            return alias + ".ob_client_id IN ("
                    + "SELECT sc.id FROM ob_clients sc WHERE sc.created_by = :" + USER_PARAM + ")";
        }
        if (OB_STEP_OWNER.equals(moduleRole)) {
            // Backup owner counts, OnboardingScopeResolver.hasStepOwnedBy's own reasoning.
            return alias + ".step_id IN ("
                    + "SELECT so.id FROM ob_journey_steps so"
                    + " WHERE so.owner_user_id = :" + USER_PARAM
                    + " OR so.backup_owner_user_id = :" + USER_PARAM + ")";
        }
        return "1 = 0";
    }
}
