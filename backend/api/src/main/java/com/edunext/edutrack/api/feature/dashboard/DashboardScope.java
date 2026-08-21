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

    /**
     * A-077 · whether this caller's figures for {@code requestedProjectId} would
     * be the project's real ones.
     *
     * <h2>Why this is needed when the queries were already safe</h2>
     *
     * <p>Nothing leaks today. Every widget query ANDs two independent
     * predicates — the caller's scope and the requested project — so a project
     * outside the scope matches no rows. That is correct, and it is why this is
     * <b>not</b> a security fix.
     *
     * <p>It is a truthfulness fix. Zero rows render as an empty chart, and an
     * empty chart states that this project has no tickets, which is false about
     * a project with five hundred of them. A-056 settled the same question for a
     * role that has no table to answer it: <i>"an empty series renders as 'no
     * tickets matched', which is a factual claim about the data and is false"</i>
     * — and the answer was to say so in words rather than draw nothing.
     *
     * <p>A-077's project dashboard is where it stops being theoretical, because
     * that screen is reached by clicking a project name on any ticket, and the
     * project master is deliberately <em>not</em> row-scoped — see
     * {@code ProjectController}, which explains that a PM who could not see a
     * project's name could not read the tickets they do own. So a caller can and
     * routinely will arrive at a project whose figures are not theirs.
     *
     * <h2>Why the server decides it and not the client</h2>
     *
     * <p>{@code GET /me} carries {@code projectIds}, so the SPA could work this
     * out for itself — and must not. CLAUDE.md's rule is that scope is resolved
     * server-side and never by a frontend filter, and {@link DashboardScope}'s
     * own reason for existing is that a rule stated twice is a rule that drifts.
     * A-056 made the identical call for {@code unavailableReason}: saying it
     * explicitly <i>"stops the frontend re-deriving the role rule for itself"</i>.
     *
     * <p><b>Not a 404.</b> The project's existence and name are already public
     * to every authenticated caller by Stream B's deliberate decision, so
     * refusing the whole page would contradict the screen that linked here and
     * would hide nothing that is not already visible in four pickers.
     *
     * @param requestedProjectId the {@code ?projectId=} filter, or null for the
     *                           caller's whole scope — which is always
     *                           answerable, since it is by definition what they
     *                           can see.
     */
    boolean coversProject(Long requestedProjectId) {
        return requestedProjectId == null
                || projectIds.isEmpty()          // Admin — unrestricted, ScopeResolver's convention
                || projectIds.contains(requestedProjectId);
    }
}
