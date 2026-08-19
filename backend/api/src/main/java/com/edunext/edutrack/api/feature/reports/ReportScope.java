package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.permission.RolePermissions;

import java.util.List;

/**
 * A-063 · who sees which rows in a report. Blueprint §2, the "Reports section"
 * row: Admin ✅ · PM ✅ · Support <i>Limited</i> · Developer, QA, Deployment
 * <i>"Own perf."</i>
 *
 * <h2>Three answers, not six</h2>
 *
 * <p>Six roles collapse to three scopes. Admin is unrestricted. PM and Support
 * are bounded by the projects they belong to — §2 words them differently
 * ("✅" against "Limited") but that difference is about <em>which reports</em>
 * are worth showing a Support user, not about which rows they may see, and the
 * row rule for both is the same one the ticket list applies. The three delivery
 * roles get their own work and nothing else.
 *
 * <h2>Why {@code ScopeResolver} is not reused, again</h2>
 *
 * <p>Same reason {@link com.edunext.edutrack.api.feature.dashboard.DashboardScope}
 * gives: {@code ScopeResolver} produces a {@code Specification<Ticket>}, and
 * these reads go to summary tables rather than to {@code tickets}. So the rule
 * is genuinely stated three times in this codebase now — over tickets, over the
 * dashboard's summary tables, and here. That is one more than is comfortable,
 * and it is recorded rather than hidden: the honest fix is a shared scope
 * vocabulary once A-066 to A-068 show what shape the report runners actually
 * need, not a premature extraction across two features that would have to be
 * redone when the first report joins {@code project_members}.
 *
 * <p>{@code ReportsIT} is what keeps this one honest against the other two.
 *
 * @param ownWorkOnly true for §2's three delivery roles. The one flag with a
 *                    security consequence: it makes {@code ?resourceId=}
 *                    inoperative rather than merely defaulted.
 * @param userId      the caller.
 * @param projectIds  the caller's projects, or <b>empty meaning
 *                    unrestricted</b> — Admin. Matching {@code ScopeResolver}'s
 *                    convention deliberately; inverting it here is exactly how
 *                    "empty scope" comes to mean deny-all in one file and
 *                    allow-all in another.
 */
record ReportScope(boolean ownWorkOnly, long userId, List<Long> projectIds) {

    static ReportScope of(CallerIdentity caller) {
        String role = caller.roleCode();
        boolean ownWorkOnly = RolePermissions.DEVELOPER.equals(role)
                || RolePermissions.QA.equals(role)
                || RolePermissions.DEPLOYMENT.equals(role);

        List<Long> projects = RolePermissions.ADMIN.equals(role) ? List.of() : caller.projectIds();
        return new ReportScope(ownWorkOnly, caller.userId(), projects);
    }

    /**
     * Whose rows the report is about.
     *
     * <p>A delivery role is <b>always its own subject</b> and a supplied
     * {@code resourceId} is discarded — honouring it would let a Developer read
     * a colleague's scorecard by guessing a user id, which is the whole of what
     * "Own perf." withholds.
     *
     * <p><b>Called once, by {@link ReportService}, which hands the result to the
     * runner.</b> A runner must read that parameter and must not call this
     * method itself: {@code scope.resourceSubject(null)} compiles, reads as
     * "resolve the subject", and returns null for every caller who is not a
     * delivery role — which is every caller the filter is for. Five runners did
     * exactly that between A-066 and B-061.
     *
     * <p>Discarded silently rather than rejected with a 400. The client is
     * usually posting back a filter bar it rendered from the descriptor, and
     * failing the request would punish the caller for our own UI; the response
     * says what was applied in {@code meta.appliedScope}, so nothing is hidden.
     */
    Long resourceSubject(Long requested) {
        // Long.valueOf, not a bare `userId`. `ownWorkOnly ? userId : requested`
        // mixes long and Long, so the conditional operator unboxes *both*
        // branches to long — and threw NullPointerException for every caller
        // who named no resource, which is the common case for an Admin or a PM.
        // Caught by ReportsIT; the unit test below now covers it directly.
        return ownWorkOnly ? Long.valueOf(userId) : requested;
    }

    /**
     * The project filter to actually apply, given what was asked for.
     *
     * <p>An out-of-scope {@code projectId} narrows to the caller's own projects
     * rather than widening to the requested one. It is not a 403: §7 of the
     * contract conventions is explicit that an out-of-scope row is a 404 and
     * never a 403, and here there is not even a row to deny — a report over a
     * project you cannot see simply has nothing of yours in it.
     */
    List<Long> projectFilter(Long requested) {
        if (requested == null) {
            return projectIds;
        }
        if (projectIds.isEmpty() || projectIds.contains(requested)) {
            return List.of(requested);
        }
        return projectIds;
    }

    /**
     * The sentence the hub and the runner both show.
     *
     * <p>Null for Admin, because "you can see everything" is not information.
     */
    String note() {
        if (ownWorkOnly) {
            return "These reports cover your own work only.";
        }
        if (projectIds.isEmpty()) {
            return null;
        }
        return "These reports cover your projects only.";
    }
}
