package com.edunext.edutrack.api.feature.onboarding.reports;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.module.ModuleAccessGuard;

/**
 * B-122 · what the caller's onboarding role lets OB-10 read, as a predicate a
 * report query can carry.
 *
 * <h2>This is A-112's rule, written a second time, and that is the risk</h2>
 *
 * <p>{@code OnboardingScopeResolver} is the module's row-scope guard and its
 * answer is a JPA {@code Specification<ObJourney>}. Every report in this
 * package is an aggregate — counts per step, averages per month, a join across
 * clients, journeys, steps and clock events — and none of them is expressible
 * as a Criteria query over a single root without contorting both the query and
 * the guard. So the rule reaches SQL here instead.
 *
 * <p><b>Two expressions of one security rule is exactly the arrangement that
 * rots</b>, so it is written down rather than discovered: a change to
 * {@code OnboardingScopeResolver} that is not made here leaves reports reading
 * rows the rest of the module refuses, and nothing fails.
 * {@code ObReportScopeIT.theSqlAndTheSpecificationSelectTheSameJourneys} runs
 * both against the same rows for all five roles and asserts they agree, which
 * is the only mechanism that makes the duplication survivable. If that test is
 * ever deleted, this class must be too.
 *
 * <p>The alternative considered and rejected was resolving the scope to a list
 * of journey ids and passing {@code IN (…)}, which is how {@code ReportScope}
 * handles projects one module over. It does not carry: a project list is
 * bounded by the org's projects, while {@code OnboardingScopeResolver}'s own
 * note says a salesperson two years in has thousands of clients — and every
 * one of those ids would land in the SQL text of every report they open.
 *
 * <h2>{@code ownerUserId} is ignored for an OB_STEP_OWNER, not refused</h2>
 *
 * <p>{@code runObReport}'s ruling, restated because it is the one place a
 * caller's input is silently discarded: answering it would let one implementor
 * read a colleague's scorecard by guessing a user id, and a 403 would wrongly
 * imply a grant exists that could be given. {@link #ownerSubject} is where that
 * happens, and {@link #appliedScope()} is what says so on the response.
 *
 * @param moduleRole the caller's {@code ONBOARDING} role, or empty string when
 *                   they hold none. Carried rather than reduced to a boolean
 *                   because it is in the {@code ETag} — two roles asking the
 *                   same URL must not share a validator, or a cache hands one
 *                   of them the other's report after a grant changes.
 * @param userId     the caller, for the two narrowed roles.
 */
record ObReportScope(String moduleRole, long userId) {

    /** Plan §3's five module roles. Mirrors {@code OnboardingScopeResolver}'s own constants. */
    static final String OB_ADMIN = "OB_ADMIN";
    static final String OB_MANAGER = "OB_MANAGER";
    static final String OB_VIEWER = "OB_VIEWER";
    static final String OB_SALES = "OB_SALES";
    static final String OB_STEP_OWNER = "OB_STEP_OWNER";

    /**
     * The bind this class's fragments reference. Bound by
     * {@link ObReportRepository} on every statement whether or not the current
     * role's fragment mentions it — an unused named parameter is harmless, and
     * binding conditionally would make "which roles need it" a second rule to
     * keep in step with the fragments themselves.
     */
    static final String USER_PARAM = "scopeUserId";

    /** The scope for one caller. */
    static ObReportScope of(CallerIdentity caller) {
        return new ObReportScope(
                caller.moduleRole(ModuleAccessGuard.ONBOARDING).orElse(""), caller.userId());
    }

    /**
     * §3's three roles that see every journey.
     *
     * <p>Viewer sits beside Admin and Manager, which is correct rather than an
     * oversight: "everything, read-only" is the same <em>row</em> set as
     * Manager's, and what separates them is what they may do with one — A-114's
     * matrix, not a scope. {@code OnboardingScopeResolver} makes the identical
     * call and states it at length.
     */
    boolean unrestricted() {
        return OB_ADMIN.equals(moduleRole) || OB_MANAGER.equals(moduleRole)
                || OB_VIEWER.equals(moduleRole);
    }

    /**
     * True for every path that is not one of §3's five rules — no grant, an
     * unknown role, and {@code TICKETING_MEMBER}, which is in
     * {@code ck_user_module_access_module_role} and means "no standing in
     * onboarding".
     *
     * <p>Reached by default rather than by enumeration, because the safe answer
     * to a misconfiguration is still nothing.
     */
    boolean deniesEverything() {
        return !unrestricted() && !OB_SALES.equals(moduleRole)
                && !OB_STEP_OWNER.equals(moduleRole);
    }

    /**
     * The scope as a SQL predicate over a query's {@code ob_journeys} alias.
     *
     * <p>Correlated subqueries rather than a join, so that a report already
     * joining steps for its own reasons cannot have its row count multiplied by
     * the scope — a step owner who owns three steps in one journey must not
     * make that journey count three times in a funnel.
     *
     * @param journeyAlias the alias the caller's own FROM clause gave
     *                     {@code ob_journeys}. Passed rather than fixed by
     *                     convention: a report that aliased it differently
     *                     would otherwise compile to SQL referencing a table
     *                     that is not in its own query, and the failure would
     *                     be at runtime.
     */
    String journeyPredicate(String journeyAlias) {
        if (unrestricted()) {
            return "1 = 1";
        }
        if (OB_SALES.equals(moduleRole)) {
            return journeyAlias + ".ob_client_id IN ("
                    + "SELECT sc.id FROM ob_clients sc WHERE sc.created_by = :" + USER_PARAM + ")";
        }
        if (OB_STEP_OWNER.equals(moduleRole)) {
            // Backup owner counts, exactly as OnboardingScopeResolver reads §3:
            // the backup exists to cover the step when the owner cannot, which
            // is impossible if the journey containing it is invisible. Both
            // columns are nullable and `= ?` never matches NULL, so an unowned
            // step gives nobody visibility.
            return journeyAlias + ".id IN ("
                    + "SELECT so.journey_id FROM ob_journey_steps so"
                    + " WHERE so.owner_user_id = :" + USER_PARAM
                    + " OR so.backup_owner_user_id = :" + USER_PARAM + ")";
        }
        return "1 = 0";
    }

    /**
     * The same rule projected onto {@code ob_clients}, for the one report whose
     * grain is a client rather than a journey.
     *
     * <p><b>For OB_SALES this is {@code created_by} directly, which is wider
     * than {@link #journeyPredicate} and deliberately so.</b> A client captured
     * yesterday with no journey yet has no row the journey predicate can match,
     * and a <em>pipeline</em> report that omitted them would answer "how much
     * have you brought in" with "how much has started work" — which is a
     * different question and the one the report is not asking. It can only ever
     * add clients the caller created themselves, never somebody else's, so it
     * widens what they see about their own book and nothing else. §3's wording
     * is "journeys whose client they created", and the client is the subject of
     * that sentence.
     *
     * <p>For OB_STEP_OWNER there is no such widening available and none is
     * wanted: their standing comes entirely from owning a step, so a client
     * with no journey is a client they have no relationship to.
     */
    String clientPredicate(String clientAlias) {
        if (unrestricted()) {
            return "1 = 1";
        }
        if (OB_SALES.equals(moduleRole)) {
            return clientAlias + ".created_by = :" + USER_PARAM;
        }
        if (OB_STEP_OWNER.equals(moduleRole)) {
            return clientAlias + ".id IN ("
                    + "SELECT sj.ob_client_id FROM ob_journeys sj"
                    + " JOIN ob_journey_steps so ON so.journey_id = sj.id"
                    + " WHERE so.owner_user_id = :" + USER_PARAM
                    + " OR so.backup_owner_user_id = :" + USER_PARAM + ")";
        }
        return "1 = 0";
    }

    /**
     * Whose rows an owner-filtered report is actually about.
     *
     * <p>The one filter with a security consequence, which is why it is
     * resolved here and not left in {@link ObReportFilters} beside the
     * preferences. An OB_STEP_OWNER always gets themselves, whatever they sent;
     * everybody else gets what they asked for, or every owner when they asked
     * for nobody.
     *
     * @param requested the {@code ?ownerUserId=} the caller sent, or null
     * @return the owner to filter on, or null for every owner
     */
    Long ownerSubject(Long requested) {
        if (OB_STEP_OWNER.equals(moduleRole)) {
            return userId;
        }
        return requested;
    }

    /**
     * The contract's {@code meta.appliedScope} — what the rows were actually
     * narrowed to, in words.
     *
     * <p>Sent even when nothing was narrowed, because the sentence is what lets
     * one caller comparing their report against a colleague's see why the
     * numbers differ without asking. The wording matches
     * {@code OnboardingScopeResolver}'s own table so that the two read as one
     * rule rather than as two that happen to agree.
     */
    String appliedScope() {
        return switch (moduleRole) {
            case OB_ADMIN, OB_MANAGER, OB_VIEWER -> "all clients";
            case OB_SALES -> "clients you created";
            case OB_STEP_OWNER -> "journeys containing your services, and your own figures only";
            default -> "nothing";
        };
    }

    /**
     * The catalogue's {@code scopeNote} — the same sentence, one screen
     * earlier, or null for a role that sees everything.
     *
     * <p>Null rather than "all clients" for the unrestricted roles: a note
     * saying nothing was narrowed is a line of furniture on every hub visit,
     * and A-118 makes the field nullable for that reason. On the run response
     * the same information <em>is</em> always sent, because there the caller
     * has filters set and needs to know which of them survived.
     */
    String scopeNote() {
        if (unrestricted()) {
            return null;
        }
        return switch (moduleRole) {
            case OB_SALES -> "You see clients you created.";
            case OB_STEP_OWNER -> "You see journeys containing your services, and reports "
                    + "broken down by owner show your own figures only.";
            default -> "You hold no onboarding role, so these reports have nothing to show.";
        };
    }
}
