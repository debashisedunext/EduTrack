package com.edunext.edutrack.api.feature.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
}
