package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.security.permission.RouteInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-121 · the two operations the contract declared are now actually served.
 *
 * <h2>The failure this catches is the one A-124 had to be fixed for</h2>
 *
 * <p>{@code openapi.yaml} has declared {@code requestObSignoffOtp} and
 * {@code verifyObSignoffOtp} since A-118, and A-120 built everything behind
 * them — the token resolver, both rate-limit budgets, the identical refusal —
 * without registering a route. A contract that looks complete and a mock that
 * answers is a screen that works in development and 404s against the real
 * backend, which is precisely how the product catalogue shipped a blank column.
 *
 * <p>Asserted against the handler mapping rather than by making requests: the
 * question is whether the routes exist at all, and coupling that to a working
 * datasource would make it a test of something else.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
class PublicSignoffOtpRoutesTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    private Set<String> otpRoutes() {
        return RouteInventory.routeKeys(mapping).stream()
                .filter(route -> route.contains("/api/v1/public/onboarding/signoff/otp"))
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("both OTP operations are served")
    void bothAreServed() {
        assertThat(otpRoutes()).containsExactlyInAnyOrder(
                "POST /api/v1/public/onboarding/signoff/otp",
                "POST /api/v1/public/onboarding/signoff/otp/verify");
    }

    @Test
    @DisplayName("no GET anywhere on the OTP tree")
    void thereIsNoGet() {
        // The contract's first standing property, and the reason OB-09 is a
        // POST-then-render page: a URL carrying the token lands in browser
        // history, in the Referer of every asset the page loads, and in the
        // access log of everything in between. ob_signoffs goes to the trouble
        // of storing only a SHA-256 so our own database cannot yield a working
        // link; a query string would give it away at the other end.
        //
        // This is the half that stays true when somebody adds a convenience GET
        // so the link can be opened directly.
        assertThat(otpRoutes()).noneMatch(route -> route.startsWith("GET "));
    }
}
