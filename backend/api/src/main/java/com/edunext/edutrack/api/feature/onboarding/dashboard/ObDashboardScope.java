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
 * <h2>🔴 The summary table has no scope dimension, and two of the five roles
 * therefore cannot be answered from it</h2>
 *
 * <p>{@code ob_dashboard_summary} is keyed {@code (stat_date, product_id)}
 * (A-108). A-112's {@code OnboardingScopeResolver} narrows OB_SALES to
 * "journeys whose client they created" and OB_STEP_OWNER to "journeys
 * containing their steps". Neither is a predicate a product-keyed
 * pre-aggregate can express — there is no column to intersect against — and
 * CLAUDE.md forbids answering a dashboard by counting the journey tables live.
 *
 * <p>So this is not a filter that has been left off. It is a genuine conflict
 * between a contract requirement ({@code getObDashboardSummary}: "Scoped by
 * A-112's {@code OnboardingScopeResolver} like every other read") and the only
 * storage the rules permit, and B-121 resolves it by <b>saying so on the
 * wire</b> rather than by picking one of the two rules to break quietly. The
 * two ways it could have been broken quietly are both worse:
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
 * <p>The real fix is a scope dimension on the summary table, which is a Stream
 * A migration and a B-120 change. Named in the backlog rather than left for a
 * bug report; {@link #unavailableReason()} is what the screen shows meanwhile.
 *
 * <p>{@code TodayStatsRepository} records the same shape of gap one module
 * over — {@code resource_daily_stats} has no project column, so a PM's
 * "my project's resources" is approximated by membership and the approximation
 * is written down. The difference here is that no approximation is available:
 * "clients I created" has no proxy among these columns at all.
 *
 * @param unrestricted true when the caller sees every journey, which is the
 *                     only case {@code ob_dashboard_summary} can serve.
 * @param moduleRole   the caller's {@code ONBOARDING} role, or empty string
 *                     when they hold none. Carried for the {@code ETag}: two
 *                     roles asking the same URL must not share a validator, or
 *                     a cache hands one of them the other's board after a
 *                     grant changes.
 */
record ObDashboardScope(boolean unrestricted, String moduleRole) {

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
        return new ObDashboardScope(isUnrestricted(role), role);
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
     * <p><b>A caller with no onboarding grant never reaches this.</b> A-111's
     * gate answers 404 for them, so "nothing" is not a sentence this method
     * has to produce for a real request — it is here because a record whose
     * behaviour depends on a guard elsewhere having run is a record that
     * misbehaves the day somebody calls it from a scheduled job.
     */
    String unavailableReason() {
        if (unrestricted) {
            return null;
        }
        return switch (moduleRole) {
            case OB_SALES, OB_STEP_OWNER -> "The board counts " + appliedScope()
                    + ", and the summary it reads is stored per product with no scope "
                    + "dimension — so this card cannot be narrowed to you yet. "
                    + "The lists below are scoped correctly.";
            default -> "You hold no onboarding role, so there is nothing to count.";
        };
    }
}
