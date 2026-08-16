package com.edunext.edutrack.api.feature.dashboard;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.permission.RolePermissions;

import java.util.List;

/**
 * A-056 · the role decision A-054 made, extracted so there is one of it.
 *
 * <h2>Why this is its own type</h2>
 *
 * <p>A-054 established the rule and held it in two private statics inside
 * {@code DashboardService}. A-056 adds a second reader — {@link WidgetService} —
 * and a rule stated twice is a rule that drifts. The failure mode is specific
 * and silent: the summary cards would scope a Developer correctly while a widget
 * on the same screen read the project table, and the screen would show them
 * their colleagues' tickets in a chart while the cards above it showed their
 * own. Nobody reads that as a leak; they read it as a chart being wrong.
 *
 * <p>{@code ScopeResolver} still cannot be reused. It produces a JPA
 * {@code Specification<Ticket>} and these reads deliberately never touch
 * {@code tickets} — CLAUDE.md forbids a live {@code COUNT(*)} behind a
 * dashboard, which is why A-050 built the summary tables at all. So the rule is
 * genuinely stated twice in the codebase: once over {@code tickets}, once over
 * the summary tables. This class makes the second one singular, and
 * {@code DashboardScopeIT} is what keeps it honest against the first.
 *
 * @param ownWorkOnly true for §2's three delivery roles, whose scope is
 *                    {@code assigned_to = me}. Decides <b>which table</b>
 *                    answers, not merely which rows: a project-keyed table
 *                    cannot express "assigned to me" however it is filtered,
 *                    and narrowing it to the projects they happen to work in
 *                    would show them their colleagues' tickets.
 * @param userId      the caller, for the resource-keyed table.
 * @param projectIds  the caller's projects, or <b>empty meaning
 *                    unrestricted</b> — Admin. That convention is
 *                    {@code ScopeResolver}'s own and is matched deliberately;
 *                    inverting it here is how "empty scope" comes to mean
 *                    deny-all in one file and allow-all in another.
 */
record DashboardScope(boolean ownWorkOnly, long userId, List<Long> projectIds) {

    static DashboardScope of(CallerIdentity caller) {
        String role = caller.roleCode();
        boolean ownWorkOnly = RolePermissions.DEVELOPER.equals(role)
                || RolePermissions.QA.equals(role)
                || RolePermissions.DEPLOYMENT.equals(role);

        List<Long> projects = RolePermissions.ADMIN.equals(role) ? List.of() : caller.projectIds();
        return new DashboardScope(ownWorkOnly, caller.userId(), projects);
    }

    /**
     * Which person's rows the resource-keyed table should answer with.
     *
     * <p>A delivery role always gets its own id, and {@code ?assigneeId=} is
     * ignored rather than honoured — answering it would let a Developer read a
     * colleague's dashboard by guessing a user id, which is the entire leak
     * {@code ownWorkOnly} exists to close. A PM or Admin may legitimately ask
     * "how is Ravi doing" (§S-05's Resource filter), and their own scope still
     * bounds it because the resource table is fed from tickets they can see.
     *
     * @return the user id to read, or null when the project-keyed table answers.
     */
    Long resourceSubject(Long requestedAssigneeId) {
        if (ownWorkOnly) {
            return userId;
        }
        return requestedAssigneeId;
    }
}
