package com.edunext.edutrack.api.feature.search;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.permission.RolePermissions;

import java.util.List;

/**
 * A-072 · §10.2's row rule, over a native ticket query.
 *
 * <h2>⚠️ This is the fourth statement of the row rule, and the first outside JPA</h2>
 *
 * <p>{@code ScopeResolver} produces a {@code Specification<Ticket>};
 * {@code DashboardScope} and {@code ReportScope} restate the rule for the
 * summary tables. This one restates it for SQL, and it is the one worth being
 * most careful about — global search reads {@code tickets} directly through
 * {@code MATCH … AGAINST}, which the Criteria API cannot express.
 * {@code TicketListSpecs} says so in its own comment and settled for
 * {@code LIKE} rather than lose the guard:
 *
 * <blockquote>"LIKE rather than the FULLTEXT index A-009 added, because
 * MATCH … AGAINST is not expressible in the Criteria API and a native query
 * here would lose the mandatory scope specification ScopedTickets ANDs on.
 * Correctness before speed; the index is there for when this becomes a native
 * query that keeps scope."</blockquote>
 *
 * <p>This is that native query, and this class is the "keeps scope" half. It is
 * a separate type rather than a string built inline for exactly that reason:
 * the predicate has one home, one test, and no way to be forgotten by whoever
 * writes the next search query.
 *
 * <h2>Every path that is not one of the four rules denies</h2>
 *
 * <p>{@code ScopeResolver}'s central warning, restated because SQL makes it
 * easier to get wrong: <b>a PM belonging to no projects must see nothing.</b>
 * {@code project_id IN ()} is not valid SQL, and the usual defence — drop the
 * predicate when the list is empty — turns that PM into an Admin silently. So
 * an empty project list resolves to {@link #DENY_ALL} rather than to an absent
 * clause, and an unrecognised role does the same.
 *
 * @param sql        the predicate to {@code AND} into a query over an aliased
 *                   {@code tickets t}. Never blank, never a bare {@code TRUE}
 *                   except for Admin — "unrestricted" is a value here, not an
 *                   absence, for {@code ScopeResolver}'s stated reason.
 * @param projectIds bound only when {@code sql} references it
 * @param userId     bound only when {@code sql} references it
 */
record SearchScope(String sql, List<Long> projectIds, long userId) {

    /** Always true. Admin, and only Admin. */
    private static final String UNRESTRICTED = "1 = 1";

    /**
     * Always false, and spelled as a literal rather than omitted.
     *
     * <p>{@code 1 = 0} rather than no clause at all: the failure this guards is
     * a caller who should see nothing being handed everything, and a missing
     * string is indistinguishable from a forgotten one.
     */
    private static final String DENY_ALL = "1 = 0";

    static SearchScope of(CallerIdentity caller) {
        String role = caller.roleCode();

        if (RolePermissions.ADMIN.equals(role)) {
            return new SearchScope(UNRESTRICTED, List.of(), caller.userId());
        }

        if (RolePermissions.PM.equals(role) || RolePermissions.SUPPORT.equals(role)) {
            List<Long> projects = caller.projectIds();
            if (projects.isEmpty()) {
                // The whole risk of this class — see the header.
                return new SearchScope(DENY_ALL, List.of(), caller.userId());
            }
            return new SearchScope("t.project_id IN (:projectIds)", projects, caller.userId());
        }

        if (RolePermissions.DEVELOPER.equals(role)
                || RolePermissions.QA.equals(role)
                || RolePermissions.DEPLOYMENT.equals(role)) {
            return new SearchScope("t.assigned_to = :scopeUserId", List.of(), caller.userId());
        }

        // An unrecognised role. Not an exception: a token carrying a role this
        // build does not know is a deployment-order problem, and the safe
        // reading of it is that the caller sees nothing.
        return new SearchScope(DENY_ALL, List.of(), caller.userId());
    }

    boolean bindsProjects() {
        return sql.contains(":projectIds");
    }

    boolean bindsUser() {
        return sql.contains(":scopeUserId");
    }
}
