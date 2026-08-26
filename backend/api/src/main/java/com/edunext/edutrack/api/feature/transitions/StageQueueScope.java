package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.permission.RolePermissions;
import com.edunext.edutrack.domain.identity.Project;
import com.edunext.edutrack.domain.identity.ProjectRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * C-062 · which projects' stage queues this caller may see.
 *
 * <h2>Project membership, deliberately not {@code ScopeResolver}</h2>
 *
 * <p>{@link com.edunext.edutrack.api.security.scope.ScopeResolver} gives a
 * Developer, QA or Deployment resource {@code assigned_to = me} on every
 * ticket read — §10.2, and correct everywhere else it applies. Under that rule
 * S-31 would answer only what the caller already holds, which is what
 * {@code frontend/src/mocks/handlers/ribbon.ts}'s own javadoc calls "the stall
 * it exists to prevent": blueprint §17 item 12 is explicit that this screen's
 * entire reason to exist is showing a QA or Deployment resource work that is
 * <em>not</em> already theirs.
 *
 * <p>Project membership is not invented here — it is
 * {@link com.edunext.edutrack.api.realtime.StageQueueSubscriptionScope}'s rule
 * (D-014), chosen for the matching WebSocket room and stated there as the
 * answer the REST read this class scopes was left to give: "a subscriber
 * refetches {@code GET /stages/queue}, which applies whatever scope C-062
 * gives it." This class is that.
 *
 * <h2>Read from the token, not from the database</h2>
 *
 * <p>Unlike {@code StageQueueSubscriptionScope}, which re-checks membership
 * per {@code SUBSCRIBE} because a socket subscription outlives the request
 * that opened it, this is an ordinary REST read: it lives and dies with one
 * request, so {@link CallerIdentity#projectIds()} — read once, from the
 * caller's own token — is exactly what A-034 already uses to scope every
 * other REST read (see that class's own javadoc). A second database round
 * trip here would buy nothing a stale-by-more-than-one-request token claim
 * does not already risk everywhere else in this codebase.
 *
 * <h2>🔴 Flagged for Stream A's review, not merged quietly</h2>
 *
 * <p>This is a narrow, self-contained carve-out — it does not touch
 * {@code ScopeResolver} itself, so {@code TicketScopeIT}'s pin on §10.2's four
 * rules is untouched and every other ticket read keeps behaving exactly as it
 * does today. But it is still a second row-visibility rule for the
 * {@code tickets} table, and {@code ScopeResolver}'s own javadoc calls that
 * kind of divergence blueprint §17's top risk. {@code StageQueuePage.tsx} and
 * this feature's {@code README.md} both name this decision as Stream A's to
 * make (Shivendra) rather than Stream C's to assume — written to unblock S-31
 * against the real backend rather than only the mock, and raised for that
 * review rather than landed silently.
 */
@Component
class StageQueueScope {

    private final ProjectRepository projects;

    StageQueueScope(ProjectRepository projects) {
        this.projects = projects;
    }

    /**
     * @return the project ids whose queues this caller may read — every
     *         active project for Admin (matching {@code ScopeResolver}'s own
     *         UNRESTRICTED answer for Admin), otherwise exactly the caller's
     *         own memberships. Never {@code null}; an empty list is deny-all,
     *         the same safe direction {@code ScopeResolver} takes for a PM on
     *         no projects.
     */
    List<Long> visibleProjectIds(CallerIdentity caller) {
        if (RolePermissions.ADMIN.equals(caller.roleCode())) {
            return projects.findByStatusOrderByNameAsc("ACTIVE").stream()
                    .map(Project::getId)
                    .toList();
        }
        return caller.projectIds();
    }
}
