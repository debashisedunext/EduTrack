package com.edunext.edutrack.api.feature.auth;

import com.edunext.edutrack.common.security.PasswordHashing;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-020 · the login endpoint against a real MySQL, a real Flyway run and a real
 * Argon2id hash. Definition of done: "works against the real backend, not only
 * mocks".
 *
 * <p>{@link AuthenticationServiceTest} proves the logic in isolation; this
 * proves the parts that only fail when they meet each other — the SQL against
 * the actual A-003 columns, the join to {@code roles}, the collation behaviour
 * of the username lookup, and a hash written by {@code common} being verified
 * by {@code api}.
 *
 * <p><b>Fixtures use codes no seed migration will ever claim.</b> Stream B's
 * B-001 seeds the six system roles of blueprint §2, and a fixture inserting its
 * own {@code DEVELOPER} row collides on {@code uq_roles_code} the moment that
 * lands — the failure Debashis fixed in {@code SchemaIntegrationIT} at 9de2ed1.
 * {@code IT_AUTH_*} keeps this suite independent of seed data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthLoginIT {

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

    /**
     * Flyway is redirected here as well as the datasource. application.yml runs
     * migrations as {@code edutrack_migrate} (A-010), which exists only in the
     * docker-compose stack — the container's own user holds the DDL rights this
     * throwaway schema needs.
     */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
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

    /**
     * Seeded once against the migrated schema. Hashed with the production
     * encoder rather than a fixed literal, so the row is exactly what A-026 or
     * Stream B's Resource Master would write.
     */
    void seedOnce() {
        if (seeded) return;

        jdbc.update("INSERT INTO roles (code, name, is_system) VALUES ('IT_AUTH_DEV', 'IT Auth Developer', 0)");
        Integer roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'IT_AUTH_DEV'", Integer.class);

        jdbc.update("INSERT INTO permissions (code, name, category) VALUES ('it.ticket.read', 'Read tickets', 'TICKET')");
        jdbc.update("INSERT INTO permissions (code, name, category) VALUES ('it.ticket.update', 'Update tickets', 'TICKET')");
        jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id FROM permissions WHERE code IN ('it.ticket.read', 'it.ticket.update')
                """, roleId);

        String hash = PasswordHashing.argon2id().encode(PASSWORD);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                   role_id, timezone, is_active, must_change_password)
                VALUES ('ITA001', 'it.asha', 'it.asha@edunext.test', ?, 'Asha Rao',
                        ?, 'Asia/Kolkata', 1, 0)
                """, hash, roleId);
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'it.asha'", Long.class);

        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                   role_id, timezone, is_active, must_change_password)
                VALUES ('ITA002', 'it.dormant', 'it.dormant@edunext.test', ?, 'Dormant Devi',
                        ?, 'Asia/Kolkata', 0, 0)
                """, hash, roleId);

        jdbc.update("INSERT INTO projects (project_code, name) VALUES ('ITAP', 'IT Auth Project')");
        Long projectId = jdbc.queryForObject("SELECT id FROM projects WHERE project_code = 'ITAP'", Long.class);
        jdbc.update("INSERT INTO project_members (project_id, user_id) VALUES (?, ?)", projectId, userId);

        seeded = true;
    }

    private ResponseEntity<String> login(String username, String password) {
        seedOnce();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"username":"%s","password":"%s"}""".formatted(username, password);
        return rest.postForEntity("/api/v1/auth/login", new HttpEntity<>(body, headers), String.class);
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getBody());
    }

    // ── the success path ────────────────────────────────────────────────────

    @Test
    @DisplayName("correct credentials return the caller's identity and resolved scope")
    void validLoginReturnsScopedIdentity() throws Exception {
        ResponseEntity<String> response = login("it.asha", PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode user = json(response).path("data").path("user");
        assertThat(user.path("displayName").asText()).isEqualTo("Asha Rao");
        assertThat(user.path("role").asText()).isEqualTo("IT_AUTH_DEV");
        assertThat(user.path("email").asText()).isEqualTo("it.asha@edunext.test");
        assertThat(user.path("timezone").asText()).isEqualTo("Asia/Kolkata");
        assertThat(user.path("permissions").isArray()).isTrue();
        assertThat(user.path("permissions").size()).isEqualTo(2);
        assertThat(user.path("projectIds").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("the username lookup is case-insensitive, as the column collation is")
    void usernameMatchIsCaseInsensitive() {
        // utf8mb4_0900_ai_ci. Asserted rather than assumed, because switching the
        // column to a _bin collation later would silently start rejecting people
        // who capitalise their own name.
        assertThat(login("IT.Asha", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── the failure paths, which must be indistinguishable ──────────────────

    @Test
    @DisplayName("an unknown user and a wrong password produce byte-identical refusals")
    void failuresAreIndistinguishable() {
        ResponseEntity<String> unknownUser = login("it.nobody", PASSWORD);
        ResponseEntity<String> wrongPassword = login("it.asha", "Wrong-Horse-9!");

        assertThat(unknownUser.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownUser.getBody()).isEqualTo(wrongPassword.getBody());
    }

    @Test
    @DisplayName("a deactivated account is refused, and refused identically")
    void deactivatedAccountIsRefusedIdentically() {
        ResponseEntity<String> dormant = login("it.dormant", PASSWORD);
        ResponseEntity<String> unknown = login("it.nobody", PASSWORD);

        assertThat(dormant.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(dormant.getBody()).isEqualTo(unknown.getBody());
    }

    @Test
    @DisplayName("the refusal is problem+json with the stable type URI")
    void refusalIsRfc9457() throws Exception {
        ResponseEntity<String> response = login("it.nobody", PASSWORD);

        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue());
        assertThat(json(response).path("type").asText())
                .isEqualTo("https://edutrack/errors/invalid-credentials");
    }

    @Test
    @DisplayName("no refusal leaks the username, the password or the reason")
    void refusalLeaksNothing() {
        assertThat(login("it.asha", "Wrong-Horse-9!").getBody())
                .doesNotContain("it.asha")
                .doesNotContain("Wrong-Horse-9!")
                .doesNotContainIgnoringCase("not found")
                .doesNotContainIgnoringCase("inactive");
    }

    // ── the A-022 seam ──────────────────────────────────────────────────────

    @Test
    @DisplayName("no access token is issued yet — that is A-022")
    void issuesNoTokenYet() throws Exception {
        JsonNode session = json(login("it.asha", PASSWORD)).path("data");

        assertThat(session.has("accessToken")).isFalse();
        assertThat(session.has("expiresIn")).isFalse();
    }
}
