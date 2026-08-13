package com.edunext.edutrack.api.security.scope;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.domain.tickets.Ticket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-034 · what each role actually sees, against real MySQL.
 *
 * <p><b>Why an IT and not a unit test on the specification.</b> A
 * {@link Specification} is a lambda; asserting on the criteria object it builds
 * would pass while the generated SQL was wrong, and the failure this suite
 * exists to catch — a predicate that silently disappears — looks like a
 * perfectly well-formed object right up to the point Hibernate renders it. The
 * only honest assertion is which rows come back.
 *
 * <p>Fixtures use {@code IT_SCOPE_*} codes, for the collision reason
 * {@code AuthLoginIT} documents.
 */
@SpringBootTest
@Testcontainers
class TicketScopeIT {

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

    /** No login happens here, but the context wires Redis-backed beans and a
     *  missing server turns a scope failure into a startup failure. */
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
    ScopedTickets tickets;

    @Autowired
    JdbcTemplate jdbc;

    private static boolean seeded;

    /*
     * The corpus. Two projects, two assignees, and one ticket that is in a
     * project but assigned to nobody — five rows chosen so that no two roles
     * can produce the same answer by accident.
     *
     *   ticket        project   assigned to
     *   IT_SCOPE-1    ALPHA     Asha
     *   IT_SCOPE-2    ALPHA     Bhavna
     *   IT_SCOPE-3    BETA      Asha
     *   IT_SCOPE-4    BETA      Bhavna
     *   IT_SCOPE-5    ALPHA     (nobody)
     */
    private long alpha;
    private long beta;
    private long asha;
    private long bhavna;

    @BeforeEach
    void seed() {
        if (!seeded) {
            jdbc.update("INSERT INTO roles (code, name, is_system) VALUES ('IT_SCOPE_ROLE', 'Scope Fixture', 0)");
            insertUser("ITS001", "its.asha", "Asha Rao");
            insertUser("ITS002", "its.bhavna", "Bhavna Iyer");
            insertProject("ITSA", "IT Scope Alpha");
            insertProject("ITSB", "IT Scope Beta");

            long a = userId("its.asha");
            long b = userId("its.bhavna");
            long p1 = projectId("ITSA");
            long p2 = projectId("ITSB");

            insertTicket("IT_SCOPE-1", p1, a);
            insertTicket("IT_SCOPE-2", p1, b);
            insertTicket("IT_SCOPE-3", p2, a);
            insertTicket("IT_SCOPE-4", p2, b);
            insertTicket("IT_SCOPE-5", p1, null);
            seeded = true;
        }
        alpha = projectId("ITSA");
        beta = projectId("ITSB");
        asha = userId("its.asha");
        bhavna = userId("its.bhavna");
    }

    // ── the four §10.2 rules ────────────────────────────────────────────────

    @Test
    @DisplayName("Admin sees every ticket, including the unassigned one")
    void adminIsUnrestricted() {
        assertThat(codesVisibleTo(caller(1L, "ADMIN", List.of())))
                .containsExactly("IT_SCOPE-1", "IT_SCOPE-2", "IT_SCOPE-3", "IT_SCOPE-4", "IT_SCOPE-5");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PM", "SUPPORT"})
    @DisplayName("PM and Support see their projects — every ticket in them, whoever it is assigned to")
    void projectScopedRoles(String role) {
        assertThat(codesVisibleTo(caller(99L, role, List.of(alpha))))
                .containsExactly("IT_SCOPE-1", "IT_SCOPE-2", "IT_SCOPE-5");
    }

    @Test
    @DisplayName("more than one project is a union, not the first one")
    void multipleProjects() {
        assertThat(codesVisibleTo(caller(99L, "PM", List.of(alpha, beta)))).hasSize(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEVELOPER", "QA", "DEPLOYMENT"})
    @DisplayName("Developer, QA and Deployment see their own tickets, across every project")
    void assigneeScopedRoles(String role) {
        assertThat(codesVisibleTo(caller(asha, role, List.of())))
                .containsExactly("IT_SCOPE-1", "IT_SCOPE-3");
    }

    @Test
    @DisplayName("an assignee-scoped role does not see the unassigned ticket in its own project")
    void unassignedIsNotVisibleToAnAssignee() {
        // assigned_to = ? never matches NULL. Stated as a test because it is
        // the behaviour somebody will one day report as a missing ticket, and
        // the answer is that it belongs to Admin's alarm, not to a queue.
        assertThat(codesVisibleTo(caller(asha, "DEVELOPER", List.of(alpha, beta))))
                .doesNotContain("IT_SCOPE-5");
    }

    @Test
    @DisplayName("membership of a project does not widen an assignee-scoped role")
    void projectsAreIgnoredForAssigneeRoles() {
        assertThat(codesVisibleTo(caller(bhavna, "QA", List.of(alpha, beta))))
                .containsExactly("IT_SCOPE-2", "IT_SCOPE-4");
    }

    // ── failing closed ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a PM in no projects sees nothing — the line this whole task turns on")
    void emptyProjectListDeniesEverything() {
        // The bug being pinned: `project_id IN ()` is not valid SQL, and
        // dropping the predicate when the list is empty promotes this caller to
        // Admin with no error and no log line.
        assertThat(codesVisibleTo(caller(99L, "PM", List.of()))).isEmpty();
    }

    @Test
    @DisplayName("an unrecognised role sees nothing, not everything")
    void unknownRoleDeniesEverything() {
        assertThat(codesVisibleTo(caller(asha, "SUPPORT_DESK", List.of(alpha)))).isEmpty();
    }

    @Test
    @DisplayName("no authentication sees nothing")
    void noCallerDeniesEverything() {
        assertThat(codesVisibleTo(null)).isEmpty();
    }

    @Test
    @DisplayName("count agrees with the rows — a total the body cannot show is a leak of its own")
    void countIsScopedToo() {
        assertThat(tickets.count(caller(asha, "DEVELOPER", List.of()), fixtureCorpus())).isEqualTo(2);
        assertThat(tickets.count(caller(99L, "PM", List.of()), fixtureCorpus())).isZero();
    }

    // ── the single-row door ─────────────────────────────────────────────────

    @Test
    @DisplayName("an out-of-scope id is empty, exactly as a non-existent one is")
    void outOfScopeIdIsIndistinguishableFromMissing() {
        Authentication developer = caller(asha, "DEVELOPER", List.of());

        assertThat(tickets.byId(developer, ticketId("IT_SCOPE-1"))).isPresent();
        assertThat(tickets.byId(developer, ticketId("IT_SCOPE-2"))).isEmpty();
        assertThat(tickets.byId(developer, 9_999_999L)).isEmpty();
    }

    @Test
    @DisplayName("the code lookup is scoped the same way the id lookup is")
    void codeLookupIsScoped() {
        Authentication developer = caller(asha, "DEVELOPER", List.of());

        assertThat(tickets.byCode(developer, "IT_SCOPE-1")).isPresent();
        assertThat(tickets.byCode(developer, "IT_SCOPE-2")).isEmpty();
    }

    @Test
    @DisplayName("canSee answers exactly what byId returns, because it runs the same rule")
    void canSeeAgreesWithById() {
        // D-013 authorises socket subscriptions with canSee. If the two ever
        // disagreed, a Developer could subscribe to live updates for a ticket
        // they cannot open — which is the leak in a different clothes.
        Authentication developer = caller(asha, "DEVELOPER", List.of());

        for (String code : List.of("IT_SCOPE-1", "IT_SCOPE-2", "IT_SCOPE-3", "IT_SCOPE-4", "IT_SCOPE-5")) {
            long id = ticketId(code);
            assertThat(tickets.canSee(developer, id))
                    .as("canSee(%s)", code)
                    .isEqualTo(tickets.byId(developer, id).isPresent());
        }
    }

    @Test
    @DisplayName("a feature's own filter narrows the scope and cannot widen it")
    void extraCriteriaCannotEscapeTheScope() {
        Authentication developer = caller(asha, "DEVELOPER", List.of());

        // A criteria matching every fixture row still yields only Asha's two.
        List<Ticket> everything = tickets.list(developer, fixtureCorpus(), Sort.by("ticketCode"));

        assertThat(everything).extracting(Ticket::getTicketCode)
                .containsExactly("IT_SCOPE-1", "IT_SCOPE-3");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private List<String> codesVisibleTo(Authentication caller) {
        return tickets.list(caller, fixtureCorpus(), Sort.by("ticketCode"))
                .stream().map(Ticket::getTicketCode).toList();
    }

    private static final List<String> CORPUS = List.of(
            "IT_SCOPE-1", "IT_SCOPE-2", "IT_SCOPE-3", "IT_SCOPE-4", "IT_SCOPE-5");

    /**
     * Restricts every assertion to this suite's own five rows, so a fixture
     * left behind by another test cannot make the Admin case fail — or, worse,
     * make a deny-all case pass for the wrong reason. Enumerated rather than a
     * {@code LIKE 'IT_SCOPE-%'}, where the underscore is itself a wildcard.
     */
    private Specification<Ticket> fixtureCorpus() {
        return (root, query, builder) -> root.get("ticketCode").in(CORPUS);
    }

    private Authentication caller(long userId, String role, List<Long> projectIds) {
        return new UsernamePasswordAuthenticationToken(
                new DevPrincipal(userId, "its.fixture", "Fixture", role, projectIds, List.of()),
                null, List.of());
    }

    private void insertUser(String empCode, String username, String fullName) {
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                   role_id, timezone, is_active, must_change_password)
                VALUES (?, ?, ?, 'not-a-real-hash', ?,
                        (SELECT id FROM roles WHERE code = 'IT_SCOPE_ROLE'), 'Asia/Kolkata', 1, 0)
                """, empCode, username, username + "@edunext.test", fullName);
    }

    private void insertProject(String code, String name) {
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, ?, 'ACTIVE')", code, name);
    }

    private void insertTicket(String code, long projectId, Long assignedTo) {
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, level, original_level, assigned_to)
                VALUES (?, ?, ?, 'MEDIUM', 'MEDIUM', ?)
                """, code, projectId, "Scope fixture " + code, assignedTo);
    }

    private long userId(String username) {
        return id("SELECT id FROM users WHERE username = ?", username);
    }

    private long projectId(String code) {
        return id("SELECT id FROM projects WHERE project_code = ?", code);
    }

    private long ticketId(String code) {
        return id("SELECT id FROM tickets WHERE ticket_code = ?", code);
    }

    private long id(String sql, String key) {
        return Optional.ofNullable(jdbc.queryForObject(sql, Long.class, key))
                .orElseThrow(() -> new IllegalStateException("fixture not seeded: " + key));
    }
}
