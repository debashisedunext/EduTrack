package com.edunext.edutrack.api.security.scope;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.permission.RolePermissions;
import com.edunext.edutrack.domain.tickets.Ticket;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * A-034 · the row-scope guard. Blueprint §10.2, PLAN.md §7.
 *
 * <p>Turns the caller into a {@link Specification} that is {@code AND}-ed into
 * every ticket query, server-side, so a role's visible rows are decided once
 * here instead of in each of the several dozen places tickets get read.
 * Blueprint §17 names scope leakage as the top risk in the system and the
 * blueprint calls this "the single most important security rule"; the reason
 * it is one class is that a rule spread over thirty controllers is right in
 * twenty-nine of them and the thirtieth leaks everything.
 *
 * <table>
 *   <caption>§10.2</caption>
 *   <tr><th>Role</th><th>Sees</th></tr>
 *   <tr><td>ADMIN</td><td>every ticket</td></tr>
 *   <tr><td>PM, SUPPORT</td><td>{@code project_id IN (their projects)}</td></tr>
 *   <tr><td>DEVELOPER, QA, DEPLOYMENT</td><td>{@code assigned_to = them}</td></tr>
 *   <tr><td>anything else</td><td>nothing</td></tr>
 * </table>
 *
 * <h2>The empty list is the whole risk of this class</h2>
 *
 * <p>A PM belonging to no projects must see <em>nothing</em>. The natural
 * expression, {@code project_id IN ()}, is not valid SQL, and the usual
 * defence — skip the predicate when the collection is empty — turns that PM
 * into an Admin without any error, log line or failing test. So an empty
 * collection is answered with an explicitly false predicate, and
 * {@code TicketScopeIT} pins it. The same reasoning covers an unrecognised
 * role and an unidentifiable caller: every path that is not one of the four
 * rules above resolves to deny-all, never to unrestricted.
 *
 * <h2>Unrestricted is a predicate, not a null</h2>
 *
 * <p>Admin returns an always-true predicate rather than {@code null} or an
 * absent specification. Composing {@code null} works in Spring Data and is the
 * shorter code, but it makes "no restriction" and "the guard was never
 * consulted" the same value, and the second is the bug this class exists to
 * make impossible to write by accident.
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * <ul>
 *   <li><b>It does not answer 403.</b> Out-of-scope rows are simply not
 *       returned; A-035 turns the resulting empty {@link java.util.Optional}
 *       into a 404. A 403 would confirm the ticket exists.</li>
 *   <li><b>It does not read {@code reportees[]}.</b> The claim is in the token
 *       and §10.2 does not use it for row scope — a manager's visibility comes
 *       from their projects, not from who reports to them. Adding it here
 *       would be inventing a rule.</li>
 *   <li><b>It does not consider permissions.</b> "May this role do this at
 *       all" is A-033's {@code @PreAuthorize}. Merging the two questions is
 *       what makes a permission model unauditable.</li>
 * </ul>
 */
@Component
public class ScopeResolver {

    /** Always true. Admin, and only Admin. */
    private static final Specification<Ticket> UNRESTRICTED =
            (root, query, builder) -> builder.conjunction();

    /** Always false. Every path that is not one of the four §10.2 rules. */
    private static final Specification<Ticket> DENY_ALL =
            (root, query, builder) -> builder.disjunction();

    /**
     * The scope for the current caller.
     *
     * @return never {@code null}; an unidentifiable caller gets deny-all
     */
    public Specification<Ticket> ticketScope(Authentication authentication) {
        return CallerIdentity.of(authentication)
                .map(ScopeResolver::ticketScope)
                .orElse(DENY_ALL);
    }

    /** Resolvable from an identity directly, for callers outside the servlet
     *  chain — D-013 authorises STOMP subscriptions from a session's principal. */
    public static Specification<Ticket> ticketScope(CallerIdentity caller) {
        return switch (caller.roleCode()) {
            case RolePermissions.ADMIN -> UNRESTRICTED;
            case RolePermissions.PM, RolePermissions.SUPPORT -> inProjects(caller.projectIds());
            case RolePermissions.DEVELOPER, RolePermissions.QA, RolePermissions.DEPLOYMENT ->
                    assignedTo(caller.userId());
            // A role code the §2 matrix does not contain. It can only arrive
            // from a token we signed or from dev-noauth properties, so it is a
            // misconfiguration rather than an attack — and the safe answer to a
            // misconfiguration is still nothing.
            default -> DENY_ALL;
        };
    }

    private static Specification<Ticket> inProjects(Collection<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return DENY_ALL;
        }
        return (root, query, builder) -> root.get("projectId").in(projectIds);
    }

    /**
     * {@code assigned_to} is nullable, and {@code = ?} never matches NULL, so
     * an unassigned ticket is invisible to every Developer, QA and Deployment
     * user. That is correct and intended — nobody is assigned it, so nobody in
     * an assignee-scoped role has a claim on it — and it is why unassigned
     * tickets need the Admin-facing alarm D-0xx's {@code UnassignedTicketScanner}
     * raises rather than being expected to surface in somebody's list.
     */
    private static Specification<Ticket> assignedTo(long userId) {
        return (root, query, builder) -> builder.equal(root.get("assignedTo"), userId);
    }
}
