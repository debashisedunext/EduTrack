package com.edunext.edutrack.api.security.dev;

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
                4L, "dev.ravi", "Ravi (fake)", "DEVELOPER", List.of(1L, 2L), List.of());

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

        // hasRole("DEVELOPER") convention, matching what A-032's chain will use
        assertThat(auth.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_DEVELOPER");
    }

    @Test
    void defaultsApplyWhenAllPropertiesAreOmitted() {
        var properties = new DevNoAuthProperties(null, null, null, null, null, null);

        assertThat(properties.userId()).isEqualTo(1L);
        assertThat(properties.username()).isEqualTo("dev.admin");
        assertThat(properties.role()).isEqualTo("ADMIN");
        assertThat(properties.projectIds()).isEmpty();
        assertThat(properties.reporteeIds()).isEmpty();
    }
}
