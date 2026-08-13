package com.edunext.edutrack.api.security.scope;

import com.edunext.edutrack.api.security.TestPrincipals;
import com.edunext.edutrack.api.security.jwt.JwtAuthoritiesConverter;
import com.edunext.edutrack.api.security.permission.RolePermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * A-036 · the matrix's third outcome — six roles × four kinds of ticket, and
 * what each of them gets back.
 *
 * <h2>Why this is separate from {@code PermissionMatrixTest}</h2>
 *
 * <p>The other two outcomes are decided by a capability, which is a fact about a
 * token: no database is involved, so that suite runs in surefire on every build.
 * This one is decided by a row — {@code assigned_to}, {@code project_id} — and
 * an assertion about which rows come back cannot be made without rows. Splitting
 * them by what they need to run keeps the capability half infrastructure-free
 * instead of dragging every case behind a Docker requirement.
 *
 * <h2>What it adds over the suites either side of it</h2>
 *
 * <p>{@code TicketScopeIT} asserts §10.2 at {@link ScopedTickets}, in Java.
 * {@code ScopedNotFoundIT} asserts that the refusal reaching the client is a 404
 * and is byte-identical to a never-existed id — for a Developer. Neither states
 * the whole grid, and the grid is where the interesting rows are: a PM sees an
 * <em>unassigned</em> ticket in their project and a Developer does not, and the
 * two facts have different causes that a per-role spot check does not separate.
 *
 * <p><b>404, never 403.</b> Every refusal below is a 404. A 403 would confirm
 * the ticket exists, which is the leak §10.2 is written to prevent and the one
 * that reads, in review, like a correct access check.
 *
 * <p><b>Why a probe controller.</b> {@code GET /tickets/{id}} is in
 * {@code contracts/openapi.yaml} and does not exist yet;
 * {@code feature/tickets} is Stream C's path. The probe is the smallest stand-in
 * for the handler they will write, registered only in this test's context. When
 * the real route lands this suite keeps testing the grid and the route joins
 * {@code PermissionMatrix}.
 *
 * <p>Fixtures use {@code IT_MTX*} identifiers, for the collision reason
 * {@code AuthLoginIT} documents.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(ScopeMatrixIT.Probe.class)
class ScopeMatrixIT {

    private static final String PROBE = "/api/v1/test/scope-matrix/";

    /** An id no fixture was ever given. */
    private static final long NEVER_ISSUED = 9_999_999L;

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

    /*
     * The corpus. One caller, one other user, two projects — and four tickets
     * chosen so that no two roles can produce the same answer by accident:
     *
     *   fixture          project   assigned to
     *   IT_MTX-MINE      ALPHA     the caller
     *   IT_MTX-THEIRS    ALPHA     somebody else
     *   IT_MTX-ELSEWHERE BETA      somebody else
     *   IT_MTX-ORPHAN    ALPHA     nobody
     */
    private static final String MINE = "IT_MTX-MINE";
    private static final String THEIRS = "IT_MTX-THEIRS";
    private static final String ELSEWHERE = "IT_MTX-ELSEWHERE";
    private static final String ORPHAN = "IT_MTX-ORPHAN";
    private static final String MISSING = "(never issued)";

    /**
     * The grid, blueprint §10.2, written out rather than derived from
     * {@link ScopeResolver} — for the reason {@code PermissionMatrix}'s javadoc
     * gives at length: an expectation computed from the implementation cannot
     * disagree with it.
     *
     * <p>Roles not named see a 404. The caller is a member of ALPHA only, so
     * the PM and Support rows are membership answers rather than blanket ones.
     */
    private static final Map<String, Set<String>> VISIBLE_TO = Map.of(

            // Their own ticket, in their own project. Every role sees this one,
            // and that is what makes the 404s below mean something rather than
            // being a guard that refuses everybody.
            MINE, RolePermissions.ROLE_CODES,

            // Somebody else's, in a project the caller belongs to. The split
            // between the two scope rules: project-scoped roles see it,
            // assignee-scoped roles do not.
            THEIRS, Set.of(RolePermissions.ADMIN, RolePermissions.PM, RolePermissions.SUPPORT),

            // A project the caller does not belong to. Admin alone.
            ELSEWHERE, Set.of(RolePermissions.ADMIN),

            // In their project, assigned to nobody. Visible to the project-scoped
            // roles and invisible to every assignee-scoped one, because
            // `assigned_to = ?` never matches NULL. That is intended — nobody is
            // assigned it, so nobody in those roles has a claim on it — and it is
            // pinned here because it will one day be reported as a missing
            // ticket. Stream D's UnassignedTicketScanner is what surfaces it.
            ORPHAN, Set.of(RolePermissions.ADMIN, RolePermissions.PM, RolePermissions.SUPPORT),

            // An id that was never issued. Nobody, including Admin — and it must
            // be indistinguishable from the rows above that answer 404.
            MISSING, Set.of());

    /**
     * The stand-in for Stream C's future handler. One line, and deliberately no
     * {@code try}, no {@code Optional} and no status code — that absence is the
     * point of {@link ScopedTickets#require}.
     */
    @TestConfiguration
    static class Probe {

        @Bean
        ScopeMatrixProbeController scopeMatrixProbeController(ScopedTickets tickets) {
            return new ScopeMatrixProbeController(tickets);
        }
    }

    @RestController
    static class ScopeMatrixProbeController {

        private final ScopedTickets tickets;

        ScopeMatrixProbeController(ScopedTickets tickets) {
            this.tickets = tickets;
        }

        @GetMapping(PROBE + "{id}")
        @PreAuthorize("isAuthenticated()")
        String probe(Authentication caller, @PathVariable long id) {
            return tickets.require(caller, id).getTicketCode();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtAuthoritiesConverter authorities;

    @Autowired
    JdbcTemplate jdbc;

    private static boolean seeded;

    private long caller;
    private long alpha;

    @BeforeEach
    void seed() {
        if (!seeded) {
            insertUser("MTX001", "mtx.caller", "Asha Rao");
            insertUser("MTX002", "mtx.other", "Bhavna Iyer");
            insertProject("MTXA", "Matrix Alpha");
            insertProject("MTXB", "Matrix Beta");

            long me = userId("mtx.caller");
            long them = userId("mtx.other");
            long a = projectId("MTXA");
            long b = projectId("MTXB");

            insertTicket(MINE, a, me);
            insertTicket(THEIRS, a, them);
            insertTicket(ELSEWHERE, b, them);
            insertTicket(ORPHAN, a, null);
            seeded = true;
        }
        caller = userId("mtx.caller");
        alpha = projectId("MTXA");
    }

    // ── the grid ────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{1} · {0} · {2}")
    @MethodSource("everyRoleTimesEveryFixture")
    void theScopeGridHolds(String fixture, String role, String expected) throws Exception {
        long id = MISSING.equals(fixture) ? NEVER_ISSUED : ticketId(fixture);

        int status = mvc.perform(get(PROBE + id).with(authentication(principal(role))))
                .andReturn().getResponse().getStatus();

        if ("200".equals(expected)) {
            assertThat(status)
                    .as("%s must be able to open %s — §10.2", role, fixture)
                    .isEqualTo(200);
        } else {
            assertThat(status)
                    .as("""
                            %s must not be able to open %s, and the refusal must be 404.

                            403 would confirm the ticket exists, which is the existence leak \
                            §10.2 is written to prevent — and it is the wrong answer that \
                            reads, in review, exactly like the right one.""", role, fixture)
                    .isEqualTo(404);
        }
    }

    static Stream<Arguments> everyRoleTimesEveryFixture() {
        return new TreeSet<>(VISIBLE_TO.keySet()).stream().flatMap(fixture ->
                new TreeSet<>(RolePermissions.ROLE_CODES).stream()
                        .map(role -> Arguments.of(
                                fixture,
                                role,
                                VISIBLE_TO.get(fixture).contains(role) ? "200" : "404")));
    }

    @Test
    @DisplayName("a PM belonging to no project sees nothing, rather than everything")
    void aProjectlessPmIsScopedToNothing() throws Exception {
        // The single highest-risk line in ScopeResolver. `project_id IN ()` is
        // not valid SQL, and the usual defence — drop the predicate when the
        // collection is empty — turns this PM into an Admin with no error, no
        // log line and no failing test. Asserted through HTTP as well as in
        // TicketScopeIT because it is the one failure whose blast radius is
        // every ticket in the system.
        var homeless = TestPrincipals.of(authorities, caller, RolePermissions.PM, List.of());

        int status = mvc.perform(get(PROBE + ticketId(MINE)).with(authentication(homeless)))
                .andReturn().getResponse().getStatus();

        assertThat(status)
                .as("a PM with no projects must see no tickets — not even one assigned to them")
                .isEqualTo(404);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Project membership is a token claim (§10.2 scopes PM and Support by
     * {@code projects[]}), so it is supplied here rather than seeded — the
     * claim is what {@code ScopeResolver} actually reads, and seeding a
     * membership table the resolver never consults would prove the wrong thing.
     */
    private org.springframework.security.authentication.AbstractAuthenticationToken principal(String role) {
        return TestPrincipals.of(authorities, caller, role, List.of(alpha));
    }

    private void insertUser(String empCode, String username, String fullName) {
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                   role_id, timezone, is_active, must_change_password)
                VALUES (?, ?, ?, 'x', ?,
                        (SELECT id FROM roles WHERE code = 'DEVELOPER'), 'UTC', 1, 0)
                """, empCode, username, username + "@edunext.test", fullName);
    }

    private void insertProject(String code, String name) {
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, ?, 'ACTIVE')",
                code, name);
    }

    private void insertTicket(String code, long projectId, Long assignedTo) {
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, level, original_level, assigned_to)
                VALUES (?, ?, ?, 'MEDIUM', 'MEDIUM', ?)
                """, code, projectId, "Scope matrix fixture " + code, assignedTo);
    }

    private long userId(String username) {
        return required("SELECT id FROM users WHERE username = ?", username);
    }

    private long projectId(String code) {
        return required("SELECT id FROM projects WHERE project_code = ?", code);
    }

    private long ticketId(String code) {
        return required("SELECT id FROM tickets WHERE ticket_code = ?", code);
    }

    private long required(String sql, String argument) {
        Long id = jdbc.queryForObject(sql, Long.class, argument);
        if (id == null) {
            throw new IllegalStateException("fixture not seeded: " + argument);
        }
        return id;
    }
}
