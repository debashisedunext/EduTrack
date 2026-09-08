package com.edunext.edutrack.api.feature.portal.tickets;

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
 * A-127 · the four operations the contract declared are now actually served.
 *
 * <h2>{@code ObProductRoutesTest}'s failure, one module over</h2>
 *
 * <p>A-124's own entry names it: "a contract that looks complete and a mock that
 * answers is a screen that works in development and 404s against the real
 * backend". These four operations were written into {@code openapi.yaml} and
 * answered by the MSW mock a commit before anything served them, deliberately so
 * that C-122 could build CP-06/07 against a mock that behaves like the server —
 * which is exactly the arrangement that hides a missing route until somebody
 * clicks the screen against a real backend.
 *
 * <p>{@code PortalRouteFilterTest}'s header says "nothing serves
 * {@code /api/v1/portal/**} yet — the tree is declared and empty until the portal
 * endpoints land". This is the task that lands them, and this test is what says
 * so in a way that keeps saying it.
 *
 * <p>Asserted against the handler mapping rather than by making requests, on that
 * class's reasoning: the question is whether the routes exist at all, and a GET
 * would reach the handler and want a datasource, which would make this a test of
 * something else. The HTTP contract is {@code PortalTicketControllerTest}'s.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
class PortalTicketRoutesTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    private Set<String> portalTicketRoutes() {
        return RouteInventory.routeKeys(mapping).stream()
                .filter(route -> route.contains("/api/v1/portal/tickets"))
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("all four reads are served")
    void allFourAreServed() {
        Set<String> routes = portalTicketRoutes();

        assertThat(routes).as("routes under /api/v1/portal/tickets").hasSize(4);
        assertThat(routes).containsExactlyInAnyOrder(
                "GET /api/v1/portal/tickets",
                "GET /api/v1/portal/tickets/{ticketId}",
                "GET /api/v1/portal/tickets/{ticketId}/comments",
                "GET /api/v1/portal/tickets/{ticketId}/attachments");
    }

    /**
     * Plan §1: "No client-raised tickets. The portal's ticketing side is
     * view-only; raising tickets stays with the support desk."
     *
     * <p>Asserted rather than left to the absence of a {@code @PostMapping},
     * because absence is what a future edit changes without noticing. A write
     * verb appearing under this prefix should fail here, on the day it is added,
     * rather than at whatever review does or does not catch it.
     */
    @Test
    @DisplayName("no write verb, because the portal's ticketing side is view-only")
    void thereIsNoWriteVerb() {
        assertThat(portalTicketRoutes())
                .noneMatch(route -> route.startsWith("POST")
                        || route.startsWith("PATCH")
                        || route.startsWith("PUT")
                        || route.startsWith("DELETE"));
    }
}
