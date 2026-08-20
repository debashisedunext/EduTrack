package com.edunext.edutrack.api.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-074 · the API documentation is closed unless something switches it on.
 *
 * <p>The api module's test-scope {@code application.properties} enables
 * springdoc for every other test here, because {@code ContractConformanceTest}
 * reads {@code /v3/api-docs} from the running application and D-005's client
 * generation depends on it. That is a convenience for the suite and it would
 * also hide the default completely — every test in the module would run with
 * the documentation open and none of them would notice if the production
 * default flipped back.
 *
 * <p>So this class turns it off again for its own context alone. It is the only
 * place the shipped default is actually exercised.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "springdoc.api-docs.enabled=false",
                "springdoc.swagger-ui.enabled=false",
        })
@Testcontainers
class ApiDocsClosedIT {

    /** A-074 · its own infrastructure — see the note in {@code SecurityHardeningIT}. */
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_it")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+00:00",
                    "--sql-mode=ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,"
                            + "ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION",
                    "--log-bin-trust-function-creators=1")
            .withUrlParam("allowPublicKeyRetrieval", "true")
            .withUrlParam("useSSL", "false")
            .withUrlParam("connectionTimeZone", "UTC");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    TestRestTemplate rest;

    /**
     * <b>404, not 401 — and the distinction is worth stating.</b> These paths
     * are not under {@code /api/**}, so the chain's authenticated rule never
     * reaches them; they fall to {@code anyRequest().permitAll()} and then find
     * no handler, because springdoc registered none. {@code SpaResourceConfig}
     * lists {@code v3/api-docs} and {@code swagger-ui} among its backend
     * prefixes, so they are refused honestly rather than being answered with
     * the SPA shell — which is the failure this asserts against, and it would
     * be a 200.
     *
     * <p>A 200 carrying {@code index.html} is the plausible regression here: it
     * looks like the documentation is gone, while an automated scan sees a
     * successful response on a well-known documentation path.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/v3/api-docs",
            "/v3/api-docs/edutrack",
            "/v3/api-docs/swagger-config",
            "/swagger-ui.html",
            "/swagger-ui/index.html",
    })
    @DisplayName("no documentation path is served when the documentation is switched off")
    void documentationPathsAreClosed(String path) {
        assertThat(rest.getForEntity(path, String.class).getStatusCode())
                .as("%s must not answer 200 with the documentation, nor 200 with the SPA shell", path)
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * The whole application must still work with the documentation closed —
     * this is the shipped configuration, so a context that only starts with
     * springdoc enabled would be a production outage discovered on deployment
     * day.
     *
     * <p>Asserted against a protected API route rather than
     * {@code /actuator/health}: health aggregates the database and Redis
     * indicators, and this context runs neither, so it answers 503 for reasons
     * that have nothing to do with springdoc. A 401 from the filter chain
     * proves what is actually in question — the context refreshed, the chain
     * built, and the server is answering.
     */
    @Test
    @DisplayName("the application still serves with the documentation closed")
    void theApplicationStillServes() {
        assertThat(rest.getForEntity("/api/v1/tickets", String.class).getStatusCode())
                .as("a refusal from the chain, not a 5xx from a context that failed to build")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Closing the documentation must not take the security headers with it —
     * they are written by a filter, and the two have no relationship beyond
     * both being A-074's.
     */
    @Test
    @DisplayName("the security headers are unaffected")
    void headersAreUnaffected() {
        assertThat(rest.getForEntity("/", String.class).getHeaders().getFirst("Content-Security-Policy"))
                .isNotBlank();
    }
}
