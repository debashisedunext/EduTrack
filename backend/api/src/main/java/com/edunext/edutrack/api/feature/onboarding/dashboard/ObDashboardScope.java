package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.module.ModuleAccessGuard;

/**
 * B-121 · what the caller's onboarding role lets the summary table answer.
 *
 * <p>{@code DashboardScope} (A-056) with a different module, and it exists for
 * the same stated reason: the role rule behind a pre-aggregated board decides
 * <b>which table answers</b>, not merely which rows, and a rule held privately
 * inside one service is a rule that drifts the moment a second reader appears.
 * B-127's slide-over and B-128's grids are those readers.
 *
 * <h2>The scope dimension landed, and two of the five roles moved tables</h2>
 *
 * <p>{@code ob_dashboard_summary} is keyed {@code (stat_date, product_id)}
 * (A-108). A-112's {@code OnboardingScopeResolver} narrows OB_SALES to
 * "journeys whose client they created" and OB_STEP_OWNER to "journeys
 * containing their steps". Neither is a predicate a product-keyed
 * pre-aggregate can express — there is no column to intersect against — and
 * CLAUDE.md forbids answering a dashboard by counting the journey tables live.
 *
 * <p>B-121 shipped those two roles a board of seven {@code unavailableReason}
 * sentences and recorded the fix as the Stream A migration it needed, rather
 * than picking one of the two rules to break quietly. Both quiet options were
 * worse and both still are:
 *
 * <ul>
 *   <li>Return the org-wide numbers to a Step Owner. Every other read they
 *       make is narrowed, so the board would disagree with the client list
 *       beside it and with the slide-over it opens — and it would disclose the
 *       size of the book to somebody scoped out of most of it.</li>
 *   <li>Return zeroes. A zero renders as "nothing is overdue", which is a
 *       factual claim about the data and is false. A-056 settled this exact
 *       question for the ticketing widgets a Developer's table cannot serve,
 *       and the answer was words rather than an empty chart.</li>
 * </ul>
 *
 * <p>{@code ob_scope_dashboard_summary} is that migration: the same stock
 * columns keyed {@code (stat_date, scope_user_id, product_id)}, written by a
 * fourth pass of B-120 for each live OB_SALES and OB_STEP_OWNER grant. So both
 * roles now read real figures, {@link #unavailableReason()} has nothing to say
 * about either, and {@link ObScopeDashboardSummaryRepository} is what answers
 * them.
 *
 * <p><b>{@link #unrestricted()} did not change and must not.</b> It is still
 * false for both roles — it is the flag that keeps them off the org-wide
 * table, and the third option above is exactly what flipping it would do.
 * "Answerable" and "unrestricted" are independent, and this class is where
 * they are kept apart.
 *
 * <p>{@code TodayStatsRepository} records the shape of gap this used to be,
 * one module over — {@code resource_daily_stats} has no project column, so a
 * PM's "my project's resources" is approximated by membership and the
 * approximation is written down. That one is still open.
 *
 * <h2>B-127 widened this with {@code userId} and two SQL predicates</h2>
 *
 * <p>The summary route above only ever needs {@link #unrestricted()} and
 * {@link #unavailableReason()} — {@code ob_dashboard_summary} has no scope
 * dimension, so OB_SALES and OB_STEP_OWNER are simply unanswerable from it.
 * {@code listObDashboardCardItems} reads {@code ob_journey_steps} and
 * {@code ob_client_prereq_tasks} directly, which do carry every column
 * {@code OnboardingScopeResolver} filters on, so <b>the two narrowed roles are
 * answerable there</b> — the task's own point, and the reason this type grew
 * rather than gaining a sibling. {@link #journeyPredicate} and
 * {@link #clientPredicate} are {@code OnboardingScopeResolver.clientCreatedBy}
 * and {@code .hasStepOwnedBy} restated as SQL text instead of a JPA
 * {@link org.springframework.data.jpa.domain.Specification}, for
 * {@code ObEscalationScope}'s own reason one package over: a
 * {@code Specification<ObJourney>} answers a JPA root this union query never
 * builds, so the rule is expressed a second time against SQL directly rather
 * than forced through a root that does not exist. Two expressions of one
 * security rule is the risk this trades for; kept in step by
 * {@code ObDashboardCardItemsIT}, which asserts both against the same fixture
 * {@code OnboardingScopeResolverIT} uses.
 *
 * @param unrestricted true when the caller sees every journey, which is the
 *                     only case {@code ob_dashboard_summary} can serve.
 * @param moduleRole   the caller's {@code ONBOARDING} role, or empty string
 *                     when they hold none. Carried for the {@code ETag}: two
 *                     roles asking the same URL must not share a validator, or
 *                     a cache hands one of them the other's board after a
 *                     grant changes.
 * @param userId       B-127 · the caller's own id, bound under
 *                     {@link #USER_PARAM} whenever {@link #journeyPredicate}
 *                     or {@link #clientPredicate} names it. Zero and unused
 *                     for every caller of the two-argument constructor, which
 *                     is every call site that only ever reads
 *                     {@link #unrestricted()}.
 */
record ObDashboardScope(boolean unrestricted, String moduleRole, long userId) {

    /** B-121's shape, kept for every call site that never needed a user id. */
    ObDashboardScope(boolean unrestricted, String moduleRole) {
        this(unrestricted, moduleRole, 0L);
    }

    /** Plan §3's five module roles. Mirrors {@code OnboardingScopeResolver}'s own constants. */
    static final String OB_ADMIN = "OB_ADMIN";
    static final String OB_MANAGER = "OB_MANAGER";
    static final String OB_VIEWER = "OB_VIEWER";
    static final String OB_SALES = "OB_SALES";
    static final String OB_STEP_OWNER = "OB_STEP_OWNER";

    /**
     * The scope for one caller.
     *
     * <p>Switches on the <em>module</em> role, never on {@code roleCode} —
     * {@code OnboardingScopeResolver}'s own first rule. A user is SUPPORT in
     * ticketing and OB_SALES in onboarding and the two say nothing about each
     * other; reading {@code roleCode} here would hand every ticketing Admin
     * the whole onboarding board, which is a grant nobody made.
     */
    static ObDashboardScope of(CallerIdentity caller) {
        String role = caller.moduleRole(ModuleAccessGuard.ONBOARDING).orElse("");
        return new ObDashboardScope(isUnrestricted(role), role, caller.userId());
    }

    /**
     * §3's three roles that see every journey.
     *
     * <p>Viewer is here beside Admin and Manager, and that is correct rather
     * than an oversight: "everything, read-only" is the same <em>row</em> set
     * as Manager's, and what separates them is what they may do with one —
     * which is A-114's matrix, not a scope. {@code OnboardingScopeResolver}
     * makes the identical call and states it at length.
     *
     * <p>Everything else is false, by default rather than by enumeration.
     * TICKETING_MEMBER, an unknown role and no role at all all land here, and
     * for the same reason the resolver gives: the safe answer to a
     * misconfiguration is still nothing.
     */
    private static boolean isUnrestricted(String role) {
        return OB_ADMIN.equals(role) || OB_MANAGER.equals(role) || OB_VIEWER.equals(role);
    }

    /**
     * The contract's {@code appliedScope} — "what A-112 narrowed the counts
     * to, in a sentence".
     *
     * <p>Sent even when nothing was narrowed, because the sentence is what
     * lets a Step Owner comparing their board against a colleague's see why
     * the numbers differ without asking. Its wording matches
     * {@code OnboardingScopeResolver}'s table so the two read as one rule.
     */
    String appliedScope() {
        return switch (moduleRole) {
            case OB_ADMIN, OB_MANAGER, OB_VIEWER -> "all clients";
            case OB_SALES -> "clients you created";
            case OB_STEP_OWNER -> "journeys containing your services";
            default -> "nothing";
        };
    }

    /**
     * Why this caller's cards carry no number, or null when they carry one.
     *
     * <p>Plain words, not a code. The client renders the sentence; deciding it
     * here is what stops the SPA re-deriving the role rule for itself, which
     * is A-056's stated reason for putting {@code unavailableReason} on the
     * wire at all.
     *
     * <h2>OB_SALES and OB_STEP_OWNER used to get a sentence here and no longer
     * do</h2>
     *
     * <p>They were unanswerable while {@code ob_dashboard_summary} was the
     * only store: keyed {@code (stat_date, product_id)}, it had no column to
     * intersect "clients you created" or "journeys containing your steps"
     * against. {@code ob_scope_dashboard_summary} is that column, so both roles
     * now read real figures from a table written for them and this method has
     * nothing to say about either. The class header carries what changed.
     *
     * <p>What has <em>not</em> changed is {@link #unrestricted()}. It is still
     * false for both, and it must stay false: it is what keeps them off the
     * org-wide table, and flipping it to make the cards fill would hand a Step
     * Owner the whole book with nothing on screen looking wrong. The two facts
     * are independent — answerable is not the same as unrestricted — which is
     * why this method no longer branches on the role at all.
     *
     * <p><b>A caller with no onboarding grant never reaches this.</b> A-111's
     * gate answers 404 for them, so the remaining sentence is not one this
     * method has to produce for a real request — it is here because a record
     * whose behaviour depends on a guard elsewhere having run is a record that
     * misbehaves the day somebody calls it from a scheduled job.
     */
    String unavailableReason() {
        if (!deniesEverything()) {
            return null;
        }
        return "You hold no onboarding role, so there is nothing to count.";
    }

    /** B-127 · the named parameter {@link #journeyPredicate} and {@link #clientPredicate} bind {@link #userId} under. */
    static final String USER_PARAM = "obDashboardScopeUserId";

    /**
     * B-127 · true for every path that is not one of the five §3 rules —
     * {@code ObEscalationScope.deniesEverything}'s own predicate. A caller here
     * gets an empty list rather than a 404: A-111's module gate is what refuses
     * a caller with no {@code ONBOARDING} entitlement at all, and a caller who
     * holds the entitlement but an unrecognised module role is a
     * misconfiguration, whose safe answer is still nothing rather than an
     * error a legitimate route should never produce.
     */
    boolean deniesEverything() {
        return !unrestricted && !OB_SALES.equals(moduleRole) && !OB_STEP_OWNER.equals(moduleRole);
    }

    /**
     * B-127 · the scope over a {@code SERVICE} row, as SQL against the query's
     * own journey and client aliases.
     *
     * <p>{@code OnboardingScopeResolver.hasStepOwnedBy} restated: a Step Owner
     * sees every step of a journey that contains at least one of their own,
     * backup owner counting for the reason that class gives at length — the
     * backup exists to cover the step, so excluding them would under-report
     * who is actually covering it.
     *
     * @param journeyAlias the query's alias for {@code ob_journeys}
     * @param clientAlias  the query's alias for {@code ob_clients}
     */
    String journeyPredicate(String journeyAlias, String clientAlias) {
        if (unrestricted) {
            return "1 = 1";
        }
        if (OB_SALES.equals(moduleRole)) {
            return clientAlias + ".created_by = :" + USER_PARAM;
        }
        if (OB_STEP_OWNER.equals(moduleRole)) {
            return journeyAlias + ".id IN ("
                    + "SELECT DISTINCT so.journey_id FROM ob_journey_steps so"
                    + " WHERE so.owner_user_id = :" + USER_PARAM
                    + " OR so.backup_owner_user_id = :" + USER_PARAM + ")";
        }
        return "1 = 0";
    }

    /**
     * B-127 · the scope over a {@code PREREQUISITE} row, as SQL against the
     * query's own client alias.
     *
     * <p>A prerequisite task carries no journey — plan §5.3's gate sits in
     * front of every journey a client holds, not inside one — so a Step
     * Owner's visibility is "does this client have a journey containing one of
     * my steps", the same set {@link #journeyPredicate} narrows to, one join
     * further out.
     *
     * @param clientAlias the query's alias for {@code ob_clients}
     */
    String clientPredicate(String clientAlias) {
        if (unrestricted) {
            return "1 = 1";
        }
        if (OB_SALES.equals(moduleRole)) {
            return clientAlias + ".created_by = :" + USER_PARAM;
        }
        if (OB_STEP_OWNER.equals(moduleRole)) {
            return clientAlias + ".id IN ("
                    + "SELECT DISTINCT j.ob_client_id FROM ob_journeys j"
                    + " WHERE j.archived_at IS NULL AND j.id IN ("
                    + "SELECT DISTINCT so.journey_id FROM ob_journey_steps so"
                    + " WHERE so.owner_user_id = :" + USER_PARAM
                    + " OR so.backup_owner_user_id = :" + USER_PARAM + "))";
        }
        return "1 = 0";
    }

    /**
     * B-128 · the scope over {@code ob_implementor_daily_stats}, one row per
     * <em>person</em> rather than per journey or client — neither
     * {@link #journeyPredicate} nor {@link #clientPredicate} answers a
     * question this table's own grain does not carry.
     *
     * <h2>OB_STEP_OWNER narrows to exactly one row: their own</h2>
     *
     * <p>The table is keyed {@code (stat_date, user_id)}, so "which rows may
     * this Step Owner see" has an exact answer for once — themselves — rather
     * than the usual "journeys containing their steps" join. A colleague's
     * workload and performance score are not this caller's to read.
     *
     * <h2>OB_SALES cannot be narrowed at all, and that is the same genuine
     * gap {@link #unavailableReason()} names for the summary board</h2>
     *
     * <p>{@code ob_implementor_daily_stats} carries no client, and therefore no
     * client-creator column — A-112's "clients you created" rule has nothing
     * in this row to intersect against, the identical shape of gap this
     * class's own header raises for {@code ob_dashboard_summary}. The two
     * quiet alternatives are both wrong for the reasons given there: handing
     * back every implementor's figures would disclose the size of the book to
     * a role scoped out of most of it, and there is no per-row zero to fall
     * back to that would not also be a false claim. So OB_SALES falls through
     * to the same {@code 1 = 0} an unrecognised role gets — a route that
     * answers with an empty list rather than a sentence, because this is a row
     * list like {@link #journeyPredicate}'s callers, not a card with a place
     * to hang {@code unavailableReason}.
     *
     * @param userAlias the query's alias for {@code ob_implementor_daily_stats}
     */
    String implementorPredicate(String userAlias) {
        if (unrestricted) {
            return "1 = 1";
        }
        if (OB_STEP_OWNER.equals(moduleRole)) {
            return userAlias + ".user_id = :" + USER_PARAM;
        }
        return "1 = 0";
    }
}
