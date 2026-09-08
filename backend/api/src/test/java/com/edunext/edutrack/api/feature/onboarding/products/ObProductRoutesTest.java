package com.edunext.edutrack.api.feature.onboarding.products;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import com.edunext.edutrack.api.security.permission.RouteInventory;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-124 · the four operations the contract declared are now actually served.
 *
 * <h2>The failure this catches is the one the task existed to fix</h2>
 *
 * <p>{@code openapi.yaml} has declared {@code listObProducts},
 * {@code createObProduct}, {@code getObProduct} and {@code updateObProduct}
 * since A-118, and the MSW mock has answered all four — so a screen built
 * against the mock worked in development. A route dump of the running
 * application listed twenty-six onboarding routes and <b>none of these</b>.
 * That gap is invisible from either side alone: the contract looks complete,
 * the mock responds, and only a request to the real backend 404s.
 *
 * <p>Asserted against the handler mapping rather than by making requests,
 * because the question is whether the routes exist at all — a 401 or a 500
 * would answer it as well as a 200, and coupling this to a working datasource
 * would make it a test of something else.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
class ObProductRoutesTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    /**
     * Through {@code RouteInventory}, the helper the permission tests already
     * use, rather than {@code RequestMappingInfo.toString()} — its format is
     * Spring's to change and asserting on it makes this a test of a toString.
     */
    private Set<String> productRoutes() {
        return RouteInventory.routeKeys(mapping).stream()
                .filter(route -> route.contains("/api/v1/onboarding/products"))
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("the catalogue's four operations are served")
    void allFourAreServed() {
        Set<String> routes = productRoutes();

        assertThat(routes).as("routes under /api/v1/onboarding/products").hasSize(4);
        assertThat(routes).containsExactlyInAnyOrder(
                "GET /api/v1/onboarding/products",
                "POST /api/v1/onboarding/products",
                "GET /api/v1/onboarding/products/{obProductId}",
                "PATCH /api/v1/onboarding/products/{obProductId}");
    }

    @Test
    @DisplayName("no DELETE, because retiring is not deleting")
    void thereIsNoDelete() {
        // isActive is the whole lifecycle. A client boarded against a product
        // still has journeys running from it, so removing the row would leave
        // those journeys naming something nothing can resolve. The contract
        // declares no delete either; this is the half that stays true when
        // somebody adds one out of symmetry.
        assertThat(productRoutes()).noneMatch(route -> route.contains("DELETE"));
    }
}
