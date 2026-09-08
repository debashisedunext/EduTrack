package com.edunext.edutrack.api.feature.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-031 · the role to landing-route mapping, blueprint §7.1.
 */
class LandingRoutesTest {

    @ParameterizedTest
    @CsvSource({
            "ADMIN,      /dashboard",
            "PM,         /dashboard",
            "DEVELOPER,  /my-tasks",
            "SUPPORT,    /tickets",
            "QA,         /stages/queue",
            "DEPLOYMENT, /stages/queue",
    })
    @DisplayName("every seeded role lands where the blueprint says it should")
    void everyRoleIsMapped(String roleCode, String expected) {
        assertThat(LandingRoutes.forRole(roleCode)).isEqualTo(expected);
    }

    @Test
    @DisplayName("all six roles are mapped, so none silently falls through")
    void noSeededRoleFallsThrough() {
        // B-001 seeds exactly these six. Asserted as a set rather than trusting
        // the parameterised cases above to stay in step with the seed: a role
        // added there and forgotten here would still pass every case that
        // exists, because a missing mapping returns the dashboard rather than
        // failing.
        for (String role : new String[]{"ADMIN", "PM", "DEVELOPER", "SUPPORT", "QA", "DEPLOYMENT"}) {
            assertThat(LandingRoutes.forRole(role))
                    .as("role %s", role)
                    .isNotNull();
        }
        assertThat(LandingRoutes.forRole("DEVELOPER")).isNotEqualTo(LandingRoutes.DASHBOARD);
        assertThat(LandingRoutes.forRole("SUPPORT")).isNotEqualTo(LandingRoutes.DASHBOARD);
        assertThat(LandingRoutes.forRole("QA")).isNotEqualTo(LandingRoutes.DASHBOARD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUDITOR", "admin", "", " ", "ROLE_ADMIN"})
    @DisplayName("an unmapped role falls back to the dashboard rather than returning null")
    void unmappedRolesFallBack(String roleCode) {
        // Null would be worse than wrong: Session omits null fields, the frontend
        // applies its own fallback, and everybody-on-the-dashboard becomes
        // indistinguishable from the map working. The WARN is what makes a role
        // B-011 added and nobody mapped visible.
        assertThat(LandingRoutes.forRole(roleCode)).isEqualTo(LandingRoutes.DASHBOARD);
    }

    @Test
    @DisplayName("a null role code does not throw")
    void nullRoleCodeIsSurvivable() {
        // Not reachable through login today - the role is NOT NULL and joined -
        // but a landing route is not worth a 500 on a path that has already
        // authenticated someone.
        assertThat(LandingRoutes.forRole(null)).isEqualTo(LandingRoutes.DASHBOARD);
    }

    @Test
    @DisplayName("matching is case-sensitive, because role codes are stored upper-case")
    void lowerCaseIsNotAMatch() {
        // Recorded rather than made lenient. B-001 seeds upper-case codes and the
        // column is not free text; accepting 'admin' here would hide a caller
        // passing something this map should never have been given.
        assertThat(LandingRoutes.forRole("admin")).isEqualTo(LandingRoutes.DASHBOARD);
    }

    /**
     * A-116 · the module-aware entry point. {@code forRole} above is unchanged
     * and still covers the role map; these cover only what the modules add.
     */
    @Nested
    @DisplayName("forUser")
    class ForUser {

        private static final List<String> BOTH = List.of("TICKETING", "ONBOARDING");

        @Test
        @DisplayName("a caller holding both modules lands on the launcher")
        void bothModulesGoToTheLauncher() {
            assertThat(LandingRoutes.forUser("ADMIN", BOTH)).isEqualTo(LandingRoutes.LAUNCHER);
        }

        @Test
        @DisplayName("the launcher wins over the role map, for every role")
        void theLauncherIsRoleIndependent() {
            // Otherwise a DEVELOPER holding both modules would land on
            // /my-tasks and never see the chooser, which is the bug that looks
            // like "the launcher does not work for some people".
            for (String role : List.of("ADMIN", "PM", "DEVELOPER", "SUPPORT", "QA", "DEPLOYMENT")) {
                assertThat(LandingRoutes.forUser(role, BOTH))
                        .as("role " + role + " holding both modules")
                        .isEqualTo(LandingRoutes.LAUNCHER);
            }
        }

        @Test
        @DisplayName("onboarding alone lands on the onboarding dashboard, not the role map")
        void onboardingOnlySkipsTheLauncher() {
            // §2.2's launcher is "for dual-module users" and A-116 says
            // single-module users skip it entirely: a chooser with one card is
            // not a choice, it is a click between somebody and the only place
            // they can go.
            assertThat(LandingRoutes.forUser("DEVELOPER", List.of("ONBOARDING")))
                    .isEqualTo(LandingRoutes.ONBOARDING_DASHBOARD);
        }

        @Test
        @DisplayName("ticketing alone is unchanged from A-031")
        void ticketingOnlyUsesTheRoleMap() {
            assertThat(LandingRoutes.forUser("DEVELOPER", List.of("TICKETING")))
                    .isEqualTo(LandingRoutes.forRole("DEVELOPER"));
        }

        @Test
        @DisplayName("no grants at all changes nothing — which is most users")
        void noModulesIsTheStatusQuo() {
            // The clause that matters most and is least visible. Every user
            // without an explicit onboarding grant must land exactly where they
            // landed before this task, or AuthLoginIT's per-role assertions —
            // whose users carry no grants — would have been rewritten to suit
            // the change rather than the change suiting them.
            for (String role : List.of("ADMIN", "PM", "DEVELOPER", "SUPPORT", "QA", "DEPLOYMENT")) {
                assertThat(LandingRoutes.forUser(role, List.of()))
                        .as("role " + role + " with no module grants")
                        .isEqualTo(LandingRoutes.forRole(role));
            }
        }

        @Test
        @DisplayName("null modules does not throw")
        void nullModulesIsSurvivable() {
            // AuthenticatedUser copies its list defensively so this should not
            // arrive, but forRole already guards its own null for the same
            // reason: a landing route is computed on a request that has already
            // authenticated somebody, and a 500 there is worse than a default.
            assertThat(LandingRoutes.forUser("ADMIN", null)).isEqualTo(LandingRoutes.DASHBOARD);
        }

        @Test
        @DisplayName("an unmapped role with no modules still falls back rather than returning null")
        void unmappedRoleStillFallsBack() {
            assertThat(LandingRoutes.forUser("AUDITOR", List.of())).isEqualTo(LandingRoutes.DASHBOARD);
        }
    }
}
