package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.module.ModuleAccessGuard;

/**
 * B-102 · A-112's row-scope rule, projected onto {@code ob_clients}.
 *
 * <p>{@code OnboardingScopeResolver} answers a JPA
 * {@code Specification<ObJourney>}, and every read in this package is a keyset
 * page or an aggregate over {@code ob_clients} with no {@code ObJourney} root
 * passing through it. So the rule reaches SQL directly, exactly as
 * {@code ObReportScope} (B-122) and {@code ObEscalationScope} (C-115) already
 * do — see {@code ObReportScope}'s own javadoc for why a second expression of
 * one security rule is the risk rather than the convenience.
 *
 * <h2>The Sales predicate is the simple one here and the Step Owner predicate
 * is not</h2>
 *
 * <p>Sales is {@code created_by} on the row itself — the same column
 * {@code fk_ob_clients_created_by} indexes, and no join at all. Step Owner is
 * the reverse of the journey rule: a client is visible because one of their
 * journeys contains a step this caller owns, so it walks
 * {@code ob_journeys → ob_journey_steps} back to a client id.
 *
 * <p><b>Backup owner counts</b>, on {@code OnboardingScopeResolver.hasStepOwnedBy}'s
 * own reasoning: the backup exists to cover the step when the owner cannot,
 * which is impossible if the client holding it answers 404.
 *
 * <p>Both owner columns are nullable and equality never matches NULL, so an
 * unowned step gives nobody visibility — the direction the resolver already
 * chose, restated here because this is the second place it has to be true.
 */
record ObClientScope(String moduleRole, long userId) {

    /** Plan §3's five module roles. Mirrors {@code OnboardingScopeResolver}'s own constants. */
    static final String OB_ADMIN = "OB_ADMIN";
    static final String OB_MANAGER = "OB_MANAGER";
    static final String OB_VIEWER = "OB_VIEWER";
    static final String OB_SALES = "OB_SALES";
    static final String OB_STEP_OWNER = "OB_STEP_OWNER";

    static final String USER_PARAM = "scopeUserId";

    static ObClientScope of(CallerIdentity caller) {
        return new ObClientScope(
                caller.moduleRole(ModuleAccessGuard.ONBOARDING).orElse(""), caller.userId());
    }

    /** §3's three roles that see every journey, and therefore every client. */
    boolean unrestricted() {
        return OB_ADMIN.equals(moduleRole) || OB_MANAGER.equals(moduleRole) || OB_VIEWER.equals(moduleRole);
    }

    /** True for no grant, an unknown role, and {@code TICKETING_MEMBER} — deny by default. */
    boolean deniesEverything() {
        return !unrestricted() && !OB_SALES.equals(moduleRole) && !OB_STEP_OWNER.equals(moduleRole);
    }

    /**
     * Whether this caller may edit a client they can see.
     *
     * <p>Plan §3's read/write split, which {@code OnboardingScopeResolver}
     * deliberately does not express — a specification decides which rows a
     * caller sees, not what they may do with one. The contract states the write
     * side for this resource: {@code PATCH} is "OB Admin, Onboarding Manager,
     * and Sales for a client they created", so Viewer and Step Owner are read
     * only. Sales needs no extra condition here because the scope predicate has
     * already reduced their visible set to clients they created — the two rules
     * meet rather than overlap.
     */
    boolean mayWrite() {
        return OB_ADMIN.equals(moduleRole) || OB_MANAGER.equals(moduleRole) || OB_SALES.equals(moduleRole);
    }

    /**
     * Whether a client authored by {@code createdBy} is inside this scope,
     * answered without a query.
     *
     * <p>Used by the duplicate-name guard, which runs <b>unscoped</b> (see
     * {@code ObClientReadRepository.namesContaining}) and then has to decide
     * which of the matches it may name back to the caller. The two roles that
     * reach it are covered exactly: an unrestricted role sees everything, and
     * Sales sees what it authored — which is the scope predicate itself, read
     * off a column the candidate query already returned.
     *
     * <p><b>OB_STEP_OWNER answers false rather than "it depends".</b> Their
     * real rule needs a join through journeys and steps, and this method has no
     * query to make one with. No step owner can reach it today — they may not
     * create a client — and under-naming is the safe direction to be wrong in:
     * the worst outcome is a match counted rather than named, which still tells
     * the boarder to go and ask.
     */
    boolean seesClientAuthoredBy(Long createdBy) {
        if (unrestricted()) {
            return true;
        }
        return OB_SALES.equals(moduleRole) && createdBy != null && createdBy == userId;
    }

    /** The scope as a SQL predicate over the query's own {@code ob_clients} alias. */
    String predicate(String alias) {
        if (unrestricted()) {
            return "1 = 1";
        }
        if (OB_SALES.equals(moduleRole)) {
            return alias + ".created_by = :" + USER_PARAM;
        }
        if (OB_STEP_OWNER.equals(moduleRole)) {
            return alias + ".id IN ("
                    + "SELECT sj.ob_client_id FROM ob_journeys sj"
                    + " JOIN ob_journey_steps ss ON ss.journey_id = sj.id"
                    + " WHERE ss.owner_user_id = :" + USER_PARAM
                    + " OR ss.backup_owner_user_id = :" + USER_PARAM + ")";
        }
        return "1 = 0";
    }
}
