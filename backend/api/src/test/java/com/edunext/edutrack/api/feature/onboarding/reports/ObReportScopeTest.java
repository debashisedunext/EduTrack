package com.edunext.edutrack.api.feature.onboarding.reports;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.module.ModuleAccessGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-122 · the SQL half of A-112's rule.
 *
 * <p>These are the unit assertions — which role gets which fragment, and what
 * is said about it on the wire. That the fragments actually select the same
 * journeys as {@code OnboardingScopeResolver}'s specification is a claim about
 * MySQL and is pinned in {@code ObReportScopeIT}; neither test replaces the
 * other, and the pair is what makes two expressions of one security rule
 * survivable.
 */
class ObReportScopeTest {

    private static final long ME = 41L;

    @ParameterizedTest
    @ValueSource(strings = {"OB_ADMIN", "OB_MANAGER", "OB_VIEWER"})
    @DisplayName("§3's three unrestricted roles narrow nothing")
    void theThreeUnrestrictedRolesSeeEveryJourney(String role) {
        ObReportScope scope = scopeOf(role);

        assertThat(scope.unrestricted()).isTrue();
        assertThat(scope.deniesEverything()).isFalse();
        assertThat(scope.journeyPredicate("j")).isEqualTo("1 = 1");
        assertThat(scope.clientPredicate("c")).isEqualTo("1 = 1");
        assertThat(scope.scopeNote()).isNull();
        assertThat(scope.appliedScope()).isEqualTo("all clients");
    }

    /**
     * Viewer sits with Admin and Manager, which is correct rather than an
     * oversight — read-only is a write rule and this is a row rule.
     */
    @Test
    void viewerSeesTheSameRowsAsManager() {
        assertThat(scopeOf("OB_VIEWER").journeyPredicate("j"))
                .isEqualTo(scopeOf("OB_MANAGER").journeyPredicate("j"));
    }

    @Test
    void salesIsNarrowedToJourneysOfClientsTheyCreated() {
        String predicate = scopeOf("OB_SALES").journeyPredicate("j");

        assertThat(predicate)
                .contains("j.ob_client_id IN")
                .contains("sc.created_by = :scopeUserId");
    }

    /**
     * The backup owner counts, which is the slightly wider reading
     * {@code OnboardingScopeResolver} takes deliberately: a backup exists to
     * cover the step when the owner cannot, and cannot do so if the journey
     * containing it is invisible.
     */
    @Test
    void aStepOwnerSeesJourneysTheyOwnOrBackUp() {
        String predicate = scopeOf("OB_STEP_OWNER").journeyPredicate("j");

        assertThat(predicate)
                .contains("so.owner_user_id = :scopeUserId")
                .contains("so.backup_owner_user_id = :scopeUserId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"TICKETING_MEMBER", "OB_INVENTED", ""})
    @DisplayName("everything that is not one of the five denies all rows")
    void anythingElseIsDenyAll(String role) {
        ObReportScope scope = scopeOf(role);

        assertThat(scope.deniesEverything()).isTrue();
        assertThat(scope.journeyPredicate("j")).isEqualTo("1 = 0");
        assertThat(scope.clientPredicate("c")).isEqualTo("1 = 0");
        assertThat(scope.appliedScope()).isEqualTo("nothing");
    }

    /**
     * The alias is a parameter, not a convention.
     *
     * <p>A report that aliased {@code ob_journeys} differently would otherwise
     * get SQL referencing a table not in its own query, and the failure would
     * be at runtime rather than at the call site.
     */
    @Test
    void thePredicateIsWrittenAgainstTheCallersOwnAlias() {
        assertThat(scopeOf("OB_SALES").journeyPredicate("jj")).startsWith("jj.ob_client_id");
        assertThat(scopeOf("OB_SALES").clientPredicate("cc")).startsWith("cc.created_by");
    }

    /**
     * The client predicate for Sales is {@code created_by} directly, which is
     * wider than the journey one and deliberately so: a client captured with no
     * journey yet is still their pipeline. It cannot reach anybody else's
     * client, which is the property that makes the widening safe.
     */
    @Test
    @DisplayName("a sales client predicate covers clients with no journey yet")
    void salesSeesTheirOwnClientsBeforeAnyJourneyExists() {
        assertThat(scopeOf("OB_SALES").clientPredicate("c"))
                .isEqualTo("c.created_by = :scopeUserId")
                .doesNotContain("ob_journeys");
    }

    @Test
    void aStepOwnersClientsComeOnlyThroughTheirSteps() {
        assertThat(scopeOf("OB_STEP_OWNER").clientPredicate("c"))
                .contains("ob_journeys")
                .contains("ob_journey_steps");
    }

    // ── the ownerUserId overrule ────────────────────────────────────────────

    /**
     * Ignored rather than refused, which is {@code runObReport}'s ruling:
     * answering it would let one implementor read a colleague's scorecard by
     * guessing a user id, and a 403 would wrongly imply a grant exists that
     * could be given.
     */
    @Test
    void aStepOwnerAskingForSomebodyElseGetsThemselves() {
        assertThat(scopeOf("OB_STEP_OWNER").ownerSubject(999L)).isEqualTo(ME);
        assertThat(scopeOf("OB_STEP_OWNER").ownerSubject(null)).isEqualTo(ME);
    }

    @Test
    void everybodyElseGetsTheOwnerTheyAskedFor() {
        assertThat(scopeOf("OB_MANAGER").ownerSubject(999L)).isEqualTo(999L);
        assertThat(scopeOf("OB_MANAGER").ownerSubject(null)).isNull();
        assertThat(scopeOf("OB_SALES").ownerSubject(999L)).isEqualTo(999L);
    }

    /**
     * The response has to say the filter was overruled, or "the filter did
     * nothing" and "the filter matched nothing" look identical on screen.
     */
    @Test
    void theAppliedScopeSaysAStepOwnerSeesOnlyTheirOwnFigures() {
        assertThat(scopeOf("OB_STEP_OWNER").appliedScope())
                .isEqualTo("journeys containing your services, and your own figures only");
    }

    @Test
    void theScopeNoteWarnsTheNarrowedRolesBeforeTheyOpenAReport() {
        assertThat(scopeOf("OB_SALES").scopeNote()).contains("clients you created");
        assertThat(scopeOf("OB_STEP_OWNER").scopeNote()).contains("your own figures only");
        assertThat(scopeOf("").scopeNote()).contains("no onboarding role");
    }

    /**
     * The role switch reads the {@code ONBOARDING} module role and never
     * {@code roleCode} — a ticketing Admin holds no onboarding standing, and
     * reading the wrong field would hand them every journey.
     */
    @Test
    @DisplayName("a ticketing ADMIN with no onboarding grant sees nothing")
    void theTicketingRoleGrantsNothingHere() {
        CallerIdentity admin = new CallerIdentity(ME, "ADMIN", List.of(), List.of("TICKETING"));

        assertThat(ObReportScope.of(admin).deniesEverything()).isTrue();
    }

    private static ObReportScope scopeOf(String moduleRole) {
        return ObReportScope.of(new CallerIdentity(
                ME, "SUPPORT", List.of(), List.of(ModuleAccessGuard.ONBOARDING),
                moduleRole.isEmpty()
                        ? Map.of()
                        : Map.of(ModuleAccessGuard.ONBOARDING, moduleRole)));
    }
}
