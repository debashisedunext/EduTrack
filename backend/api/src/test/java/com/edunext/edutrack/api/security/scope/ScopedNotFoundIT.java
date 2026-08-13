package com.edunext.edutrack.api.security.scope;

import com.edunext.edutrack.common.security.PasswordHashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-035 · an out-of-scope id and an id that was never issued must be
 * indistinguishable, through the real chain.
 *
 * <p><b>Why a real login and not a mocked principal.</b> The property under
 * test is what reaches the client, and that is produced by Spring's
 * {@code problemdetails} rendering of an {@code ErrorResponse} — not by any
 * code in this repository. A unit test on {@link TicketNotFoundException} would
 * assert our intent, pass, and prove nothing about the bytes. This suite signs
 * in, gets a token from the same endpoint the SPA uses, and reads the response.
 *
 * <p><b>Why a probe controller.</b> {@code GET /tickets/{id}} is specified in
 * {@code contracts/openapi.yaml} but does not exist yet, and
 * {@code feature/tickets} is Stream C's path. The probe below is the smallest
 * possible stand-in for the handler they will write — one line, calling
 * {@link ScopedTickets#require} — registered only in this test's context, so it
 * never reaches production and is invisible to {@code RouteAuthorizationTest}'s
 * own context. When the real route lands, this suite keeps testing the
 * mechanism and A-036's matrix tests the route.
 *
 * <p>Fixtures use {@code IT_404_*} codes, for the collision reason
 * {@code AuthLoginIT} documents.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(ScopedNotFoundIT.Probe.class)
class ScopedNotFoundIT {

    private static final String PASSWORD = "Correct-Horse-1!";
    private static final String PROBE = "/api/v1/test/scope-probe/";

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

    /**
     * The stand-in for Stream C's future handler. One line, and deliberately
     * no {@code try}, no {@code Optional}, no status code — that absence is the
     * point of {@link ScopedTickets#require}.
     */
    @TestConfiguration
    static class Probe {

        @Bean
        ScopeProbeController scopeProbeController(ScopedTickets tickets) {
            return new ScopeProbeController(tickets);
        }
    }

    @RestController
    static class ScopeProbeController {

        private final ScopedTickets tickets;

        ScopeProbeController(ScopedTickets tickets) {
            this.tickets = tickets;
        }

        @GetMapping(PROBE + "{id}")
        @PreAuthorize("isAuthenticated()")
        String probe(Authentication caller, @PathVariable long id) {
            return tickets.require(caller, id).getTicketCode();
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    private static boolean seeded;

    private long mine;
    private long theirs;

    @BeforeEach
    void seed() {
        if (!seeded) {
            jdbc.update("""
                    INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                       role_id, timezone, is_active, must_change_password)
                    VALUES ('IT404A', 'it404.asha', 'it404.asha@edunext.test', ?, 'Asha Rao',
                            (SELECT id FROM roles WHERE code = 'DEVELOPER'), 'Asia/Kolkata', 1, 0)
                    """, PasswordHashing.argon2id().encode(PASSWORD));
            jdbc.update("""
                    INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                       role_id, timezone, is_active, must_change_password)
                    VALUES ('IT404B', 'it404.bhavna', 'it404.bhavna@edunext.test', 'x', 'Bhavna Iyer',
                            (SELECT id FROM roles WHERE code = 'DEVELOPER'), 'Asia/Kolkata', 1, 0)
                    """);
            jdbc.update("INSERT INTO projects (project_code, name, status) VALUES ('IT404', 'Not Found Fixture', 'ACTIVE')");
            insertTicket("IT_404-MINE", "it404.asha");
            insertTicket("IT_404-THEIRS", "it404.bhavna");
            seeded = true;
        }
        mine = ticketId("IT_404-MINE");
        theirs = ticketId("IT_404-THEIRS");
    }

    // ── the guarantee ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a Developer's own ticket comes back, so the 404s below mean something")
    void inScopeSucceeds() throws Exception {
        ResponseEntity<String> response = probe(mine, signIn());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("IT_404-MINE");
    }

    @Test
    @DisplayName("somebody else's ticket is 404, never 403 — a 403 would confirm it exists")
    void outOfScopeIsNotFound() throws Exception {
        assertThat(probe(theirs, signIn()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the refusal is RFC 9457 the frontend can branch on")
    void refusalIsProblemJson() throws Exception {
        ResponseEntity<String> response = probe(theirs, signIn());

        assertThat(response.getHeaders().getContentType())
                .hasToString(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        JsonNode problem = new ObjectMapper().readTree(response.getBody());
        assertThat(problem.path("type").asText()).isEqualTo("https://edutrack/errors/ticket-not-found");
        assertThat(problem.path("status").asInt()).isEqualTo(404);
    }

    @Test
    @DisplayName("out-of-scope and never-existed are byte-identical, which is the whole task")
    void theTwoRefusalsAreIndistinguishable() throws Exception {
        String token = signIn();

        String outOfScope = probe(theirs, token).getBody();
        String neverExisted = probe(9_999_999L, token).getBody();

        // `instance` is the requested path, so it differs between these two by
        // construction — and it cannot carry the distinction, because it is
        // echoed from the request rather than derived from the outcome. That is
        // asserted separately below. Every field that *could* carry it must
        // match exactly: a `detail` reworded for one case and not the other
        // would restore the leak behind a matching status code.
        assertThat(withoutInstance(outOfScope)).isEqualTo(withoutInstance(neverExisted));
    }

    @Test
    @DisplayName("`instance` echoes the request path, so it says nothing about the outcome")
    void instanceIsEchoedNotDerived() throws Exception {
        String token = signIn();

        assertThat(instanceOf(probe(theirs, token).getBody())).isEqualTo(PROBE + theirs);
        assertThat(instanceOf(probe(9_999_999L, token).getBody())).isEqualTo(PROBE + 9_999_999L);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private ResponseEntity<String> probe(long ticketId, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        return rest.exchange(PROBE + ticketId, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String signIn() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"username":"it404.asha","password":"%s"}""".formatted(PASSWORD);
        ResponseEntity<String> response =
                rest.postForEntity("/api/v1/auth/login", new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new ObjectMapper().readTree(response.getBody()).path("data").path("accessToken").asText();
    }

    private static String withoutInstance(String problemJson) throws Exception {
        ObjectNode problem = (ObjectNode) new ObjectMapper().readTree(problemJson);
        problem.remove("instance");
        return problem.toString();
    }

    private static String instanceOf(String problemJson) throws Exception {
        return new ObjectMapper().readTree(problemJson).path("instance").asText();
    }

    private void insertTicket(String code, String assigneeUsername) {
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, level, original_level, assigned_to)
                VALUES (?, (SELECT id FROM projects WHERE project_code = 'IT404'), ?, 'MEDIUM', 'MEDIUM',
                        (SELECT id FROM users WHERE username = ?))
                """, code, "Not-found fixture " + code, assigneeUsername);
    }

    private long ticketId(String code) {
        Long id = jdbc.queryForObject("SELECT id FROM tickets WHERE ticket_code = ?", Long.class, code);
        if (id == null) {
            throw new IllegalStateException("fixture not seeded: " + code);
        }
        return id;
    }
}
