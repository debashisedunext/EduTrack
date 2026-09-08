package com.edunext.edutrack.api.feature.onboarding.communications;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.module.ModuleAccessGuard;

/**
 * C-112 · A-112's row-scope rule over {@code ob_step_communications} —
 * {@code ObEscalationScope}'s expression of it, column for column, because
 * the two tables carry the same two scoping keys.
 *
 * <p>{@code ob_step_communications} denormalises {@code ob_client_id} and
 * {@code step_id} onto every row (the DDL's own note: "denormalised for the
 * client-level stitched view, which reads every communication for one client
 * across every journey"), so both predicates reach the row without joining
 * through {@code ob_journeys} — exactly the reason {@code ObEscalationScope}
 * is simpler than {@code ObReportScope}'s equivalent.
 *
 * <p><b>Why a fourth copy of one rule rather than a shared class.</b> The
 * duplication is real and it is the risk {@code ObReportScope}'s own javadoc
 * names. It is repeated here anyway on that class's own reasoning:
 * {@code OnboardingScopeResolver} answers a JPA {@code
 * Specification<ObJourney>}, and neither read in this package puts an {@code
 * ObJourney} root in front of the database — both are {@code JdbcClient}
 * statements. Extracting the shared thing is a Stream A refactor across four
 * packages, not a thing to do quietly from inside the fourth.
 */
record ObCommunicationScope(String moduleRole, long userId) {

    /** Plan §3's five module roles. Mirrors {@code OnboardingScopeResolver}'s own constants. */
    static final String OB_ADMIN = "OB_ADMIN";
    static final String OB_MANAGER = "OB_MANAGER";
    static final String OB_VIEWER = "OB_VIEWER";
    static final String OB_SALES = "OB_SALES";
    static final String OB_STEP_OWNER = "OB_STEP_OWNER";

    static final String USER_PARAM = "scopeUserId";

    static ObCommunicationScope of(CallerIdentity caller) {
        return new ObCommunicationScope(
                caller.moduleRole(ModuleAccessGuard.ONBOARDING).orElse(""), caller.userId());
    }

    /** §3's three roles that see every journey, and therefore every communication. */
    boolean unrestricted() {
        return OB_ADMIN.equals(moduleRole) || OB_MANAGER.equals(moduleRole) || OB_VIEWER.equals(moduleRole);
    }

    /** True for no grant, an unknown role, and {@code TICKETING_MEMBER} — deny by default. */
    boolean deniesEverything() {
        return !unrestricted() && !OB_SALES.equals(moduleRole) && !OB_STEP_OWNER.equals(moduleRole);
    }

    /** The scope as a SQL predicate over the query's own {@code ob_step_communications} alias. */
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
