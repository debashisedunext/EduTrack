package com.edunext.edutrack.api.security.permission;

import com.edunext.edutrack.common.security.PasswordHashing;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-033 · the permission model end to end, against a real MySQL and Redis.
 *
 * <h2>The link nothing else proves</h2>
 *
 * <p>There is a chain of five hand-offs between an Admin ticking a box in S-09
 * and a route refusing a Developer:
 *
 * <pre>
 *   role_permissions → AuthUserRepository → the JWT permissions claim
 *                    → JwtAuthoritiesConverter → @PreAuthorize
 * </pre>
 *
 * <p>Every one of them has a unit test, and every one of those unit tests
 * supplies its own input. {@code RouteAuthorizationTest} builds a token from
 * {@link RolePermissions} — the static mirror — so it would pass identically if
 * the database granted something else entirely; {@code PermissionCatalogTest}
 * reads the migration as text and never runs it. The join in the middle is the
 * one thing only a real login can exercise, and it is where a mismatch between
 * the seeded {@code SUPPORT}/{@code SUPPORT_DESK} codes would have surfaced.
 *
 * <p>So this signs in as real users of real roles and checks what actually comes
 * back in the token, then what the server does with it. Fixtures use
 * {@code IT_PERM_*} identifiers for the collision reason {@code AuthLoginIT}
 * documents, but the <b>roles are the seeded ones</b> — an invented role would
 * have no {@code role_permissions} rows, and a token with an empty
 * {@code permissions} array would make every assertion here pass for the wrong
 * reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PermissionModelIT {

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
        if (seeded) {
            return;
        }
        seedUser("IPM001", "ipm.admin", RolePermissions.ADMIN);
        seedUser("IPM002", "ipm.dev", RolePermissions.DEVELOPER);
        seedUser("IPM003", "ipm.support", RolePermissions.SUPPORT);
        seeded = true;
    }

    private void seedUser(String empCode, String username, String roleCode) {
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                   role_id, timezone, is_active, must_change_password)
                VALUES (?, ?, ?, ?, ?, (SELECT id FROM roles WHERE code = ?), 'UTC', 1, 0)
                """,
                empCode, username, username + "@edunext.test",
                PasswordHashing.argon2id().encode(PASSWORD), username, roleCode);
    }

    // ------------------------------------------------------------------
    // the claim
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the access token carries exactly the role's seeded grants")
    void theTokenCarriesTheSeededGrants() throws Exception {
        // The assertion is against RolePermissions, so this is simultaneously
        // the check that the static mirror matches what the database actually
        // returns through a live join — not merely what the migration text says.
        for (String role : List.of(
                RolePermissions.ADMIN, RolePermissions.DEVELOPER, RolePermissions.SUPPORT)) {

            assertThat(permissionsIn(signIn(usernameFor(role))))
                    .as("permissions claim for %s", role)
                    .containsExactlyInAnyOrderElementsOf(RolePermissions.of(role));
        }
    }

    @Test
    @DisplayName("the role claim is the corrected SUPPORT code, not SUPPORT_DESK")
    void theSupportRoleCodeSurvivesTheRoundTrip() throws Exception {
        assertThat(claim(signIn("ipm.support"), "role")).isEqualTo("\"SUPPORT\"");
    }

    // ------------------------------------------------------------------
    // what the server does with it
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a Developer is refused a master write, with 403")
    void aDeveloperCannotWriteMasterData() throws Exception {
        String developer = signIn("ipm.dev");

        assertThat(delete("/api/v1/masters/holidays/1", developer).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(delete("/api/v1/masters/leaves/1", developer).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a Support user is refused a resource write — Admin holds resource.manage alone")
    void supportCannotManageResources() throws Exception {
        // Support holds ten permissions, which is why this one is worth
        // asserting separately from the Developer's: the refusal has to come
        // from the absence of resource.manage specifically, not from the caller
        // being broadly unprivileged.
        ResponseEntity<String> response = patch(
                "/api/v1/users/1/status", signIn("ipm.support"), "{\"isActive\":false}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("an Admin holding master.write is not refused the same route")
    void anAdminIsNotRefused() throws Exception {
        // Against a real database, so this is a genuine 404 for holiday id
        // 999999 rather than "something past authorisation failed". The
        // distinction matters: a chain that refused everybody would satisfy
        // every deny assertion above.
        assertThat(delete("/api/v1/masters/holidays/999999", signIn("ipm.admin")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a permission refusal is 403, not 401 — the caller is signed in")
    void refusalDoesNotLookLikeAnExpiredSession() throws Exception {
        // 401 would send the frontend's interceptor into a re-login loop for a
        // session that is perfectly valid. Asserted here as well as in
        // RouteAuthorizationTest because this one goes through the real
        // BearerTokenAuthenticationFilter, which has its own entry point and
        // was already caught changing a refusal's shape once in A-032.
        assertThat(delete("/api/v1/masters/holidays/1", signIn("ipm.dev")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("routes that need only authentication stay open to every role")
    void anAuthenticatedRouteIsNotAccidentallyRestricted() throws Exception {
        // The direction that a permission model gets wrong quietly: over-
        // restricting a read nobody notices until a Developer opens a screen.
        // The resource directory is the assignee picker and the @mention source,
        // and it must answer for all six roles.
        for (String username : List.of("ipm.admin", "ipm.dev", "ipm.support")) {
            assertThat(get("/api/v1/users", signIn(username)).getStatusCode())
                    .as("GET /users as %s", username)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String usernameFor(String roleCode) {
        return switch (roleCode) {
            case RolePermissions.ADMIN -> "ipm.admin";
            case RolePermissions.DEVELOPER -> "ipm.dev";
            case RolePermissions.SUPPORT -> "ipm.support";
            default -> throw new IllegalArgumentException("no fixture for " + roleCode);
        };
    }

    private String signIn(String username) throws Exception {
        seedOnce();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/auth/login",
                new HttpEntity<>("""
                        {"username":"%s","password":"%s"}""".formatted(username, PASSWORD), headers),
                String.class);

        assertThat(response.getStatusCode()).as("login as %s", username).isEqualTo(HttpStatus.OK);
        return new ObjectMapper().readTree(response.getBody())
                .path("data").path("accessToken").asText();
    }

    /**
     * Reads the payload without verifying the signature.
     *
     * <p>Deliberate: the point is to see what the server put in the token, and
     * decoding it through our own {@code JwtDecoder} would test the round trip
     * of our own encoder rather than the contents. The signature is
     * {@code SecurityChainIT}'s subject.
     */
    private static String claim(String accessToken, String name) throws Exception {
        String payload = new String(Base64.getUrlDecoder()
                .decode(accessToken.split("\\.")[1]));
        return new ObjectMapper().readTree(payload).path(name).toString();
    }

    private static List<String> permissionsIn(String accessToken) throws Exception {
        String payload = new String(Base64.getUrlDecoder()
                .decode(accessToken.split("\\.")[1]));
        List<String> codes = new ArrayList<>();
        new ObjectMapper().readTree(payload).path("permissions")
                .forEach(node -> codes.add(node.asText()));
        return codes;
    }

    private ResponseEntity<String> get(String path, String bearer) {
        return exchange(path, HttpMethod.GET, bearer, null);
    }

    private ResponseEntity<String> delete(String path, String bearer) {
        return exchange(path, HttpMethod.DELETE, bearer, null);
    }

    private ResponseEntity<String> patch(String path, String bearer, String body) {
        return exchange(path, HttpMethod.PATCH, bearer, body);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, String bearer, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }
}
