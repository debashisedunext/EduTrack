package com.edunext.edutrack.api.arch;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * A-115 · the onboarding module and the ticketing module stay separable.
 *
 * <h2>Why a rule and not a convention</h2>
 *
 * <p>The separation is already honoured. Nothing under
 * {@code api.feature.onboarding} imports {@code api.feature.tickets} or
 * {@code api.feature.transitions} today, and on the frontend
 * {@code features/onboarding/journey/ribbon/} says so in its own comments —
 * "Built fresh, not imported from {@code components/ribbon/}". That is exactly
 * the state worth locking: a decision held by everyone remembering it, which
 * survives until the afternoon somebody needs one helper and reaches for the
 * nearest one.
 *
 * <p>What the separation buys is stated in A-118's contract note: the two
 * modules' dashboards, reports, notification templates and notification
 * centres are their own rather than new keys on the ticketing ones, because
 * "the shapes differ", {@code ObChannel} and {@code NotificationChannel} are
 * not subsets of each other, and — the load-bearing one — <b>the gate is on
 * the route tree</b>, so a 404 on one key of a shared route would tell a
 * ticketing-only user that the onboarding module exists. A shared class is how
 * a shared route arrives later.
 *
 * <h2>Both directions, deliberately</h2>
 *
 * <p>"Separable" is symmetric and the reverse is the more likely accident:
 * ticketing is the older, larger module, and a new onboarding type is exactly
 * the sort of thing a ticketing service would import for a field it happens to
 * need. Testing one direction would leave the module pair coupled in the
 * direction nobody was watching.
 *
 * <h2>The append-only half needed one line, not a rule</h2>
 *
 * The append-only half of A-115 needed one line, not a rule, and that is
 * worth recording rather than leaving as an absence.
 *
 * <p>{@code V20260903_1745__ob_step_append_only_pair.sql} creates
 * {@code ob_step_history} and {@code ob_step_communications} with the same
 * hash chain and triggers as the ticketing three. Both service-layer checks
 * already reach them:
 *
 * <ul>
 *   <li>{@code ObStepHistoryRepository} implements
 *       {@code AppendOnly<ObStepHistory>}, and {@code AppendOnlyRulesTest}
 *       keys on that marker rather than on a list of table names — so its
 *       no-mutator and no-mutating-route rules covered the new pair the day
 *       the repository was declared, with no edit here.</li>
 *   <li>Its {@code PROTECTED_RESOURCE} pattern matches on the path segment,
 *       so {@code .../history} is already refused a PUT, PATCH or DELETE
 *       under {@code /onboarding} exactly as under {@code /tickets}.</li>
 * </ul>
 *
 * <p>The one genuine gap was {@code communications}, which that pattern did
 * not name; it does now. {@code ob_step_communications} has no entity or
 * repository yet, so the service-layer rules have nothing to bind to — the
 * pattern is what will refuse the route the day one appears, which is the
 * order these two halves arrive in.
 *
 * <p>Adding a second, onboarding-specific copy of those rules here was the
 * obvious move and the wrong one: two rules over one invariant drift, and
 * the copy that is not run is the one that matters.
 *
 * <h2>What this rule does not say</h2>
 *
 * <p>It does not forbid the two modules sharing {@code common}, {@code domain}
 * or {@code api.security}. Those are the deliberate shared floor — the scope
 * guard, the audit trail, the working calendar — and a rule that pushed the
 * modules apart there would produce a second copy of {@code CallerIdentity},
 * which is the outcome CLAUDE.md's "one home per rule" exists to prevent.
 */
class ObModuleSeparationTest {

    private static final String ONBOARDING = "..api.feature.onboarding..";
    private static final String PORTAL = "..api.feature.portal..";
    private static final String TICKETS = "..api.feature.tickets..";
    private static final String TRANSITIONS = "..api.feature.transitions..";

    @Test
    @DisplayName("onboarding does not reach into ticketing")
    void onboardingDoesNotDependOnTicketing() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(ONBOARDING, PORTAL)
                .should().dependOnClassesThat().resideInAnyPackage(TICKETS, TRANSITIONS)
                .because("""
                        the two modules are sold and entitled separately, and A-111's gate \
                        is on the route tree — so a type shared across the boundary is how \
                        a route ends up shared, and a 404 on one key of a shared route \
                        tells a ticketing-only user the onboarding module exists. Anything \
                        genuinely common belongs in common, domain or api.security.""");
        rule.check(ProductionClasses.get());
    }

    @Test
    @DisplayName("ticketing does not reach into onboarding")
    void ticketingDoesNotDependOnOnboarding() {
        // The direction more likely to break. Ticketing is the older and larger
        // module; an onboarding type is precisely what one of its services
        // would import for a field it happens to need, and nobody is watching
        // this way round.
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(TICKETS, TRANSITIONS)
                .should().dependOnClassesThat().resideInAnyPackage(ONBOARDING, PORTAL)
                .because("""
                        separable is symmetric. A ticketing service holding an onboarding \
                        type makes the ticketing module undeployable without the onboarding \
                        one, which is the same coupling read from the other end.""");
        rule.check(ProductionClasses.get());
    }

}
