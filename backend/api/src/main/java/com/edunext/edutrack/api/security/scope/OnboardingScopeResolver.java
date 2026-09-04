package com.edunext.edutrack.api.security.scope;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.module.ModuleAccessGuard;
import com.edunext.edutrack.domain.onboarding.ObClient;
import com.edunext.edutrack.domain.onboarding.ObJourney;
import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * A-112 · the row-scope guard for the onboarding module. Onboarding plan §3.
 *
 * <p>The counterpart of {@link ScopeResolver} for a second module, built the
 * same way and for the same reason: one class turns the caller into a
 * {@link Specification} that is {@code AND}-ed into every journey query, so a
 * role's visible rows are decided once here rather than in each place a journey
 * is read. {@link ScopedJourneys} is the only class that composes it.
 *
 * <table>
 *   <caption>Onboarding plan §3</caption>
 *   <tr><th>Module role</th><th>Sees</th></tr>
 *   <tr><td>OB_ADMIN</td><td>everything</td></tr>
 *   <tr><td>OB_MANAGER</td><td>every journey</td></tr>
 *   <tr><td>OB_VIEWER</td><td>everything, read-only</td></tr>
 *   <tr><td>OB_SALES</td><td>journeys whose client they created</td></tr>
 *   <tr><td>OB_STEP_OWNER</td><td>journeys containing their steps</td></tr>
 *   <tr><td>anything else</td><td>nothing</td></tr>
 * </table>
 *
 * <h2>This switches on the module role, not on {@code roleCode}</h2>
 *
 * <p>A user is {@code SUPPORT} in ticketing and {@code OB_SALES} in onboarding,
 * and the two say nothing about each other — the fixture corpus has exactly
 * that pairing. {@code roleCode} is the ticketing-era role and is what
 * {@link ScopeResolver} switches on; this reads
 * {@link CallerIdentity#moduleRole(String)} instead. Using {@code roleCode}
 * here would give every Admin of the ticketing system every onboarding journey,
 * which is a grant nobody made.
 *
 * <h2>Read-only is not expressed here</h2>
 *
 * <p>OB_VIEWER "everything, read-only" and OB_STEP_OWNER "read, update only
 * their own steps" both have a write half, and neither is in this class. A
 * specification decides which <em>rows</em> a caller sees; whether they may
 * change one is A-114's permission matrix and the step-level check that goes
 * with it. Merging the two questions is what makes a permission model
 * unauditable — the same split {@link ScopeResolver} states.
 *
 * <p>So a Viewer and a Manager get an identical specification from this class.
 * That is correct and not an oversight: they see the same journeys, and what
 * separates them is what they may do with one.
 *
 * <h2>Deny is the default on every path that is not one of the five</h2>
 *
 * <p>An unidentifiable caller, a caller with no grant in {@code ONBOARDING},
 * and a caller holding a module role the {@code ck_user_module_access_module_role}
 * CHECK does not contain all resolve to deny-all. The third can only arrive
 * from a token we signed or from {@code dev-noauth} properties, so it is a
 * misconfiguration rather than an attack — and the safe answer to a
 * misconfiguration is still nothing.
 *
 * <p>{@code TICKETING_MEMBER} is in that CHECK and is deliberately <em>not</em>
 * a case below. It is the role a ticketing-only user holds, and it means "no
 * standing in onboarding" — which is deny-all, reached by the default branch
 * rather than by a case that would have to be kept in step with it.
 */
@Component
public class OnboardingScopeResolver {

    /** Always true. OB_ADMIN, OB_MANAGER and OB_VIEWER. */
    private static final Specification<ObJourney> UNRESTRICTED =
            (root, query, builder) -> builder.conjunction();

    /** Always false. Every path that is not one of the five §3 rules. */
    private static final Specification<ObJourney> DENY_ALL =
            (root, query, builder) -> builder.disjunction();

    static final String OB_ADMIN = "OB_ADMIN";
    static final String OB_MANAGER = "OB_MANAGER";
    static final String OB_VIEWER = "OB_VIEWER";
    static final String OB_SALES = "OB_SALES";
    static final String OB_STEP_OWNER = "OB_STEP_OWNER";

    /**
     * The scope for the current caller.
     *
     * @return never {@code null}; an unidentifiable caller gets deny-all
     */
    public Specification<ObJourney> journeyScope(Authentication authentication) {
        return CallerIdentity.of(authentication)
                .map(OnboardingScopeResolver::journeyScope)
                .orElse(DENY_ALL);
    }

    /** Resolvable from an identity directly, for callers outside the servlet chain. */
    public static Specification<ObJourney> journeyScope(CallerIdentity caller) {
        return switch (caller.moduleRole(ModuleAccessGuard.ONBOARDING).orElse("")) {
            case OB_ADMIN, OB_MANAGER, OB_VIEWER -> UNRESTRICTED;
            case OB_SALES -> clientCreatedBy(caller.userId());
            case OB_STEP_OWNER -> hasStepOwnedBy(caller.userId());
            default -> DENY_ALL;
        };
    }

    /**
     * §3 · Sales sees "clients they created".
     *
     * <p>A correlated subquery rather than reading the id list first and
     * passing {@code IN (…)}. The list form would be a second round trip whose
     * result grows without bound — a salesperson two years in has thousands of
     * clients — and would put every one of those ids into the SQL text. This
     * keeps the whole question in one statement, against the index the foreign
     * key already provides — {@code fk_ob_clients_created_by}.
     */
    private static Specification<ObJourney> clientCreatedBy(long userId) {
        return (root, query, builder) -> {
            Subquery<Long> mine = query.subquery(Long.class);
            var client = mine.from(ObClient.class);
            mine.select(client.get("id"))
                    .where(builder.equal(client.get("createdBy"), userId));
            return root.get("obClientId").in(mine);
        };
    }

    /**
     * §3 · Step Owner sees "journeys containing their steps".
     *
     * <p><b>Backup owner counts.</b> A step carries {@code owner_user_id} and
     * {@code backup_owner_user_id}, and the backup exists to cover the step when
     * the owner cannot — which is impossible if the journey containing it
     * answers 404. Reading §3's wording literally as {@code owner_user_id} only
     * would make the backup column decorative, so this is deliberately the
     * slightly wider reading and is stated here rather than discovered as a
     * bug report.
     *
     * <p>Both columns are nullable and {@code = ?} never matches NULL, so an
     * unowned step gives nobody visibility. That is the intended direction:
     * a journey whose steps are all unassigned is visible to Managers, Admins
     * and Viewers, and to the Sales user who created the client — not to a
     * Step Owner who happens to own steps elsewhere.
     *
     * <p>Both sides are indexed — {@code ix_ob_journey_steps_owner} and the
     * foreign key's {@code fk_ob_journey_steps_backup_owner} — so the OR does
     * not cost a scan.
     */
    private static Specification<ObJourney> hasStepOwnedBy(long userId) {
        return (root, query, builder) -> {
            Subquery<Long> mine = query.subquery(Long.class);
            var step = mine.from(ObJourneyStep.class);
            mine.select(step.get("journeyId"))
                    .where(builder.or(
                            builder.equal(step.get("ownerUserId"), userId),
                            builder.equal(step.get("backupOwnerUserId"), userId)));
            return root.get("id").in(mine);
        };
    }
}
