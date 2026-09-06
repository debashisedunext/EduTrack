package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-121 · which module roles the summary table can answer.
 *
 * <p>Written independently of {@link ObDashboardScope}'s own switch, from plan
 * §3's table and {@code OnboardingScopeResolver}'s javadoc — the same
 * discipline {@code PermissionMatrix} states at length. A test that reads the
 * production switch to build its expectation passes on every build and proves
 * nothing.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ObDashboardScopeTest {

    @ParameterizedTest
    @ValueSource(strings = {"OB_ADMIN", "OB_MANAGER", "OB_VIEWER"})
    @DisplayName("the three roles that see every journey are the three the table can serve")
    void unrestrictedRolesReadTheSummary(String role) {
        ObDashboardScope scope = ObDashboardScope.of(caller(role));

        assertThat(scope.unrestricted()).isTrue();
        assertThat(scope.unavailableReason()).isNull();
        assertThat(scope.appliedScope()).isEqualTo("all clients");
    }

    /**
     * Viewer is deliberately beside Admin and Manager. "Everything, read-only"
     * is the same <em>row</em> set as Manager's, and what separates them is
     * what they may do with one — A-114's matrix, not a scope.
     * {@code OnboardingScopeResolver} makes the identical call.
     */
    @Test
    @DisplayName("a Viewer sees the same board as a Manager; read-only is not a scope")
    void viewerAndManagerGetTheSameBoard() {
        assertThat(ObDashboardScope.of(caller("OB_VIEWER")).unrestricted())
                .isEqualTo(ObDashboardScope.of(caller("OB_MANAGER")).unrestricted());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OB_SALES", "OB_STEP_OWNER"})
    @DisplayName("a narrowed role is told the board cannot be narrowed, not shown everyone's")
    void narrowedRolesGetWordsRatherThanNumbers(String role) {
        ObDashboardScope scope = ObDashboardScope.of(caller(role));

        assertThat(scope.unrestricted()).isFalse();
        // The failure this pins is the quiet one: `unrestricted` returning true
        // here would hand a Step Owner the org-wide board and nothing on screen
        // would look wrong.
        assertThat(scope.unavailableReason())
                .contains("no scope dimension")
                .contains(scope.appliedScope());
    }

    @Test
    void a_sales_caller_is_told_they_see_clients_they_created() {
        assertThat(ObDashboardScope.of(caller("OB_SALES")).appliedScope())
                .isEqualTo("clients you created");
    }

    @Test
    void a_step_owner_is_told_they_see_journeys_containing_their_services() {
        assertThat(ObDashboardScope.of(caller("OB_STEP_OWNER")).appliedScope())
                .isEqualTo("journeys containing your services");
    }

    /**
     * Deny is the default on every path that is not one of the five, which is
     * {@code OnboardingScopeResolver}'s own rule. TICKETING_MEMBER is in
     * {@code user_module_access}' CHECK and means "no standing in onboarding";
     * the unknown role can only arrive from a token we signed or from
     * {@code dev-noauth} properties, and the safe answer to a misconfiguration
     * is still nothing.
     */
    @ParameterizedTest
    @ValueSource(strings = {"TICKETING_MEMBER", "OB_SUPERUSER", ""})
    void anything_that_is_not_one_of_the_five_counts_nothing(String role) {
        ObDashboardScope scope = ObDashboardScope.of(caller(role));

        assertThat(scope.unrestricted()).isFalse();
        assertThat(scope.appliedScope()).isEqualTo("nothing");
        assertThat(scope.unavailableReason()).contains("no onboarding role");
    }

    /**
     * The ticketing role says nothing about the onboarding one. A caller who is
     * ADMIN in ticketing and holds no onboarding grant must not reach the
     * board — reading {@code roleCode} here would hand every ticketing Admin
     * the whole module, which is a grant nobody made.
     */
    @Test
    @DisplayName("a ticketing ADMIN with no onboarding grant is not an onboarding admin")
    void theTicketingRoleIsNotConsulted() {
        CallerIdentity ticketingAdmin =
                new CallerIdentity(9, "ADMIN", List.of(), List.of("TICKETING"), Map.of());

        assertThat(ObDashboardScope.of(ticketingAdmin).unrestricted()).isFalse();
    }

    private static CallerIdentity caller(String moduleRole) {
        return new CallerIdentity(
                42, "SUPPORT", List.of(), List.of("ONBOARDING"), Map.of("ONBOARDING", moduleRole));
    }
}
