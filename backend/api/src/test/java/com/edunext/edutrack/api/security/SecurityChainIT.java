package com.edunext.edutrack.api.security;

import com.edunext.edutrack.common.security.PasswordHashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-032 · the filter chain, end to end against a real database and Redis.
 *
 * <p>What only a full context can prove: that the chain actually refuses an
 * unauthenticated request, that the routes deliberately left public really are,
 * that a revoked token stops working everywhere rather than only where a service
 * happens to check, and that the SPA still loads. Unit slices cannot answer any
 * of those, because each is a property of the assembled chain.
 *
 * <p>Fixtures use {@code IT_CHAIN_*} codes, for the collision reason
 * {@code AuthLoginIT} documents.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SecurityChainIT {

    private static final String PASSWORD = "Correct-Horse-1!";

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

    @Autowired
    JdbcTemplate jdbc;

    private static boolean seeded;

    @BeforeAll
    static void resetSeedFlag() {
        seeded = false;
    }

    void seedOnce() {
        if (seeded) return;
        jdbc.update("INSERT INTO roles (code, name, is_system) VALUES ('IT_CHAIN_DEV', 'Chain Dev', 0)");
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                   role_id, timezone, is_active, must_change_password)
                VALUES ('ITC001', 'itc.asha', 'itc.asha@edunext.test', ?, 'Asha Rao',
                        (SELECT id FROM roles WHERE code = 'IT_CHAIN_DEV'), 'Asia/Kolkata', 1, 0)
                """, PasswordHashing.argon2id().encode(PASSWORD));
        seeded = true;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private ResponseEntity<String> get(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String signIn() throws Exception {
        seedOnce();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"username":"itc.asha","password":"%s"}""".formatted(PASSWORD);
        ResponseEntity<String> response =
                rest.postForEntity("/api/v1/auth/login", new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new ObjectMapper().readTree(response.getBody()).path("data").path("accessToken").asText();
    }

    // ── the chain refuses ───────────────────────────────────────────────────

    @Test
    @DisplayName("a protected route without a token is refused")
    void protectedRouteRequiresAToken() {
        seedOnce();
        assertThat(get("/api/v1/me/notifications", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-a-jwt",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.wrong-signature",
            // 'none' algorithm - the classic JWT bypass. macAlgorithm(HS256) in
            // JwtDecoderConfig is what refuses it; asserted here because that one
            // line looks like decoration until something proves it load-bearing.
            "eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIiwiZXhwIjo5OTk5OTk5OTk5fQ.",
    })
    @DisplayName("a forged or malformed token is refused")
    void forgedTokensAreRefused(String token) {
        seedOnce();
        assertThat(get("/api/v1/me/notifications", token).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the 401 is problem+json the frontend can branch on")
    void refusalIsRfc9457() throws Exception {
        seedOnce();
        ResponseEntity<String> response = get("/api/v1/me/notifications", null);

        // Spring's default entry point answers an empty body with a
        // WWW-Authenticate header, which would make this the one response in the
        // application problemTypes.ts cannot read - and it is the response that
        // means "your session ended".
        assertThat(response.getHeaders().getContentType())
                .hasToString(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        JsonNode problem = new ObjectMapper().readTree(response.getBody());
        assertThat(problem.path("type").asText()).isEqualTo("https://edutrack/errors/invalid-access-token");
        assertThat(problem.path("status").asInt()).isEqualTo(401);
        assertThat(problem.path("instance").asText()).isEqualTo("/api/v1/me/notifications");
    }

    @Test
    @DisplayName("the refusal does not say which check failed")
    void refusalsAreIndistinguishable() {
        seedOnce();
        // Missing, malformed and wrongly-signed must be byte-identical, for
        // AccessTokenVerifier's reason: naming the failed check tells someone
        // probing with forged tokens how close they came.
        String missing = get("/api/v1/me/notifications", null).getBody();
        String malformed = get("/api/v1/me/notifications", "not-a-jwt").getBody();
        String badSignature = get("/api/v1/me/notifications",
                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.wrong").getBody();

        assertThat(malformed).isEqualTo(missing);
        assertThat(badSignature).isEqualTo(missing);
    }

    // ── the chain admits ────────────────────────────────────────────────────

    @Test
    @DisplayName("a valid token reaches a protected route")
    void aValidTokenIsAdmitted() throws Exception {
        String token = signIn();

        // Any status but 401 proves the chain admitted it; what the endpoint then
        // does is not this task's business.
        assertThat(get("/api/v1/me/notifications", token).getStatusCode())
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/webhooks/email/inbound",
            "/api/v1/webhooks/email/bounce",
    })
    @DisplayName("the six operations the contract marks security:[] are reachable without a token")
    void publicApiRoutesAreReachable(String path) {
        seedOnce();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response =
                rest.postForEntity(path, new HttpEntity<>("{}", headers), String.class);

        // 400 or 401-from-the-controller are both fine - what matters is that the
        // CHAIN did not refuse. A 401 carrying invalid-access-token would mean it
        // did; the auth endpoints' own refusals carry different type URIs.
        assertThat(response.getBody() == null ? "" : response.getBody())
                .as("%s must not be refused by the filter chain", path)
                .doesNotContain("invalid-access-token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/v3/api-docs"})
    @DisplayName("health and the API document stay reachable")
    void publicInfrastructureIsReachable(String path) {
        seedOnce();
        assertThat(get(path, null).getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("actuator endpoints that publish internals are not public")
    void revealingActuatorEndpointsAreProtected() {
        seedOnce();
        // metrics publishes per-endpoint request counts and JVM internals. Health
        // is public but show-details: when-authorized, so anonymous callers see
        // only up or down.
        assertThat(get("/actuator/metrics", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the SPA shell loads without a token")
    void theSpaShellIsNotBehindAuthentication() {
        seedOnce();
        // ScaffoldSecurityConfig's javadoc warned A-032 about exactly this: miss
        // the static assets and the UI fails for authenticated users too, and it
        // presents as a blank page rather than an auth error because the shell
        // itself never loads. 404 is fine here - the packaged index.html is not
        // on the test classpath - but 401 is not.
        assertThat(get("/", null).getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/tickets", null).getStatusCode())
                .as("a client-side route must reach the SPA, not the chain")
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── revocation · the half A-025 could not enforce ───────────────────────

    @Test
    @DisplayName("a revoked token is refused everywhere, not only where a service checks")
    void revokedTokensAreRefusedByTheChain() throws Exception {
        String token = signIn();
        assertThat(get("/api/v1/me/notifications", token).getStatusCode())
                .as("precondition: the token works before logout")
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        assertThat(rest.exchange("/api/v1/auth/logout", HttpMethod.POST,
                new HttpEntity<>(headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // This is the assertion A-025 wrote its blacklist for and could not make:
        // the token is still signed by us and still unexpired, and must now be
        // refused anyway.
        assertThat(get("/api/v1/me/notifications", token).getStatusCode())
                .as("logout must stop the access token, not merely the refresh token")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a revoked token is refused identically to a forged one")
    void revocationIsNotDistinguishable() throws Exception {
        String token = signIn();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        rest.exchange("/api/v1/auth/logout", HttpMethod.POST, new HttpEntity<>(headers), String.class);

        // "Revoked" versus "never valid" would tell someone holding a stolen
        // token whether the account they took it from is still live.
        assertThat(get("/api/v1/me/notifications", token).getBody())
                .isEqualTo(get("/api/v1/me/notifications", "not-a-jwt").getBody());
    }
}
