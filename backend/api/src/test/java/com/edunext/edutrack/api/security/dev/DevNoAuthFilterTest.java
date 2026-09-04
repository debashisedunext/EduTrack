package com.edunext.edutrack.api.security.dev;

import com.edunext.edutrack.api.security.permission.RolePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-012. The filter itself: every request comes out the other side carrying
 * the configured fake principal, in the exact shape A-022's real JWT
 * principal will use.
 */
class DevNoAuthFilterTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void stampsEveryRequestWithTheConfiguredPrincipal() throws Exception {
        var properties = new DevNoAuthProperties(
                4L, "dev.ravi", "Ravi (fake)", "DEVELOPER", List.of(1L, 2L), List.of(), List.of("TICKETING", "ONBOARDING"));

        new DevNoAuthFilter(properties).doFilter(
                new MockHttpServletRequest("GET", "/api/v1/tickets"),
                new MockHttpServletResponse(),
                new MockFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();

        DevPrincipal principal = (DevPrincipal) auth.getPrincipal();
        assertThat(principal.userId()).isEqualTo(4L);
        assertThat(principal.username()).isEqualTo("dev.ravi");
        assertThat(principal.role()).isEqualTo("DEVELOPER");
        assertThat(principal.projectIds()).containsExactly(1L, 2L);
        assertThat(principal.reporteeIds()).isEmpty();

        // hasRole("DEVELOPER") convention, matching what A-032's chain uses
        assertThat(auth.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_DEVELOPER");
    }

    /**
     * A-033. Before this, the filter granted the role authority and nothing
     * else — invisible while no route asserted a permission, and a 403 on every
     * {@code dev-noauth} request the moment one did. That would have broken
     * Streams B, C and D on the profile that exists to unblock them, so the
     * grants ship in the same commit as the annotations.
     */
    @Test
    void grantsTheRolesPermissionsAndNotSomebodyElses() throws Exception {
        var properties = new DevNoAuthProperties(
                4L, "dev.ravi", "Ravi (fake)", "DEVELOPER", List.of(), List.of(), List.of("TICKETING", "ONBOARDING"));

        new DevNoAuthFilter(properties).doFilter(
                new MockHttpServletRequest("GET", "/api/v1/tickets"),
                new MockHttpServletResponse(),
                new MockFilterChain());

        var granted = SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                .stream().map(GrantedAuthority::getAuthority).toList();

        assertThat(granted)
                .as("exactly the §2 grants for DEVELOPER, plus the role authority")
                .containsExactlyInAnyOrderElementsOf(
                        java.util.stream.Stream.concat(
                                        java.util.stream.Stream.of("ROLE_DEVELOPER"),
                                        RolePermissions.of("DEVELOPER").stream())
                                .toList());

        // The half that matters more: a local environment quietly granting more
        // than production would make every permission bug invisible until
        // deployment, in the one environment where people try things out.
        assertThat(granted)
                .as("a Developer holds no master write in §2, so dev-noauth must not grant one")
                .doesNotContain("master.write", "resource.manage", "audit.view");
    }

    /**
     * An unknown role degrades to "the role authority and no permissions"
     * rather than throwing. A role an Admin adds through S-09 after this jar was
     * built must not turn every local request into a 500.
     */
    @Test
    void anUnknownRoleGrantsNoPermissions() throws Exception {
        var properties = new DevNoAuthProperties(
                9L, "dev.new", "New (fake)", "AUDITOR", List.of(), List.of(), List.of("TICKETING", "ONBOARDING"));

        new DevNoAuthFilter(properties).doFilter(
                new MockHttpServletRequest("GET", "/api/v1/tickets"),
                new MockHttpServletResponse(),
                new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_AUDITOR");
    }

    @Test
    void defaultsApplyWhenAllPropertiesAreOmitted() {
        var properties = new DevNoAuthProperties(null, null, null, null, null, null, List.of("TICKETING", "ONBOARDING"));

        assertThat(properties.userId()).isEqualTo(1L);
        assertThat(properties.username()).isEqualTo("dev.admin");
        assertThat(properties.role()).isEqualTo("ADMIN");
        assertThat(properties.projectIds()).isEmpty();
        assertThat(properties.reporteeIds()).isEmpty();
    }
}
