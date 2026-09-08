package com.edunext.edutrack.api.security.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-114 · the ratchet under {@link ObPermissionMatrix}.
 *
 * <h2>Coverage is the assertion</h2>
 *
 * <p>Same reasoning {@code RouteAuthorizationTest} gives for the platform
 * matrix: "is this route's rule correct?" can only be asked about a route
 * somebody remembered to think about, which is never the one that goes wrong.
 * The failure worth catching is the onboarding route added on a Tuesday with no
 * module-role rule at all — and CLAUDE.md's Definition of Done says a
 * permission-matrix entry for every role, which this turns from an intention
 * into a build failure.
 *
 * <p>No infrastructure, so it runs in surefire on every build.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
class ObPermissionMatrixTest {

    /**
     * By name: actuator contributes a second {@code RequestMappingHandlerMapping}
     * and by type this is ambiguous. The MVC one serves {@code /api/**}.
     */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    private Set<String> onboardingRoutes() {
        return RouteInventory.routeKeys(handlerMapping).stream()
                .filter(key -> key.contains("/api/v1/onboarding/"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @DisplayName("every onboarding route has a module-role entry")
    void everyRouteIsCovered() {
        Set<String> missing = new TreeSet<>(onboardingRoutes());
        missing.removeAll(ObPermissionMatrix.ENTRIES.keySet());

        assertThat(missing)
                .as("onboarding routes with no entry in ObPermissionMatrix — add one, "
                        + "taken from Onboarding-Module-Plan.md §3, and if the code does not "
                        + "yet apply it add the route to NOT_YET_ENFORCED as well")
                .isEmpty();
    }

    @Test
    @DisplayName("no entry names a route that does not exist")
    void noStaleEntries() {
        // The other direction, and it needs asserting separately: a matrix that
        // accumulates entries for deleted routes reads as thorough and is
        // steadily describing a different application.
        Set<String> stale = new TreeSet<>(ObPermissionMatrix.ENTRIES.keySet());
        stale.removeAll(onboardingRoutes());

        assertThat(stale)
                .as("ObPermissionMatrix entries for routes the application no longer serves")
                .isEmpty();
    }

    @Test
    @DisplayName("every entry names at least one role, and only real ones")
    void everyEntryNamesRealRoles() {
        // An empty set would read as "nobody may do this", which is not a rule
        // §3 states anywhere and is far more likely to be a half-finished edit.
        ObPermissionMatrix.ENTRIES.forEach((route, roles) -> {
            assertThat(roles).as("roles for " + route).isNotEmpty();
            assertThat(ObPermissionMatrix.ALL_ROLES)
                    .as("unknown module role in the entry for " + route
                            + " — the vocabulary is ck_user_module_access_module_role's")
                    .containsAll(roles);
        });
    }

    @Test
    @DisplayName("OB_ADMIN reaches every onboarding route")
    void adminReachesEverything() {
        // §3's first row is "OB Admin | Everything". A matrix that refused the
        // Admin somewhere would be describing a rule the plan does not have,
        // and it is the kind of slip a copy-pasted role set produces.
        Set<String> adminIsRefused = ObPermissionMatrix.ENTRIES.entrySet().stream()
                .filter(e -> !e.getValue().contains(ObPermissionMatrix.OB_ADMIN))
                .map(java.util.Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(adminIsRefused).isEmpty();
    }

    @Test
    @DisplayName("the unenforced set names only routes the matrix declares")
    void unenforcedSetIsWellFormed() {
        Set<String> unknown = new TreeSet<>(ObPermissionMatrix.NOT_YET_ENFORCED);
        unknown.removeAll(ObPermissionMatrix.ENTRIES.keySet());

        assertThat(unknown)
                .as("NOT_YET_ENFORCED names routes the matrix does not declare")
                .isEmpty();
    }

    @Test
    @DisplayName("every declared rule is enforced — the gap is zero")
    void theEnforcementGapIsClosed() {
        // A-114 shipped this asserting 26 declared and 25 unenforced, and by the
        // time A-117 and C-112 had landed it read 38 and 34 — the exact number
        // pinned each time so it could not grow quietly. A-122 is the task that
        // empties it: ObModuleRoleFilter now applies every rule in
        // ObModuleRoleRules on every request.
        //
        // The set stays, and stays asserted. Deleting it on reaching zero would
        // remove the thing that notices the next rule arriving without an
        // enforcement point — which is exactly how the first thirty-four
        // accumulated.
        assertThat(ObPermissionMatrix.NOT_YET_ENFORCED)
                .as("routes declaring a module-role rule the code does not apply")
                .isEmpty();
    }

    @Test
    @DisplayName("the matrix is the shipped rules, not a second opinion about them")
    void theMatrixIsTheShippedRules() {
        // The property that makes every other assertion in this file mean
        // something. While the table lived here, a test could agree with itself
        // about rules the application had never heard of — which is what it did
        // for thirty-four of thirty-eight routes.
        assertThat(ObPermissionMatrix.ENTRIES)
                .isEqualTo(new com.edunext.edutrack.api.security.module.ObModuleRoleRules().asRouteKeys());
    }
}
