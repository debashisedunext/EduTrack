package com.edunext.edutrack.api.feature.clients;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-066 · S-32's Client 360 view, against real MySQL.
 *
 * <h2>What a mock cannot prove</h2>
 *
 * <p>{@code Client360Service} composes {@link com.edunext.edutrack.api.security.scope.ScopedTickets}
 * twice — once for the rolled-up figures, once for the page — and the whole
 * point of the screen is that both halves narrow the same way per role. A
 * mocked {@code ScopedTickets} would return whatever the test told it to and
 * prove nothing about whether the real specification composes correctly with
 * {@code clientId} and {@code status}, which is {@link TicketScopeIT}'s own
 * reason for being an IT rather than a unit test.
 *
 * <p>Fixture rows are prefixed {@code ITC360} so nothing collides with another
 * suite's data in the shared schema this container starts fresh.
 */
@SpringBootTest
@Testcontainers
class Client360IT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_client_360_it")
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
    Client360Service service;

    @Autowired
    JdbcTemplate jdbc;

    /*
     * One client, two projects, three assignees — chosen so Admin, a
     * project-scoped role and an assignee-scoped role cannot agree by
     * accident.
     *
     *   ticket        project   assigned to   status         planned   actual
     *   ITC360-1      ALPHA     Asha          CLOSED         -5d       -6d  (met)
     *   ITC360-2      ALPHA     Bhavna        CLOSED         -5d       -3d  (missed)
     *   ITC360-3      BETA      Asha          NEW            —         —
     *   ITC360-4      BETA      Bhavna        IN_PROGRESS    —         —
     *   ITC360-5      ALPHA     (nobody)      CLOSED         —         -8d  (not committed)
     *   ITC360-6      ALPHA     Chandan       NEW            —         —
     */
    private long clientId;
    private long alpha;
    private long beta;
    private long asha;
    private long bhavna;
    private long chandan;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO roles (code, name, is_system) VALUES ('ITC360_ROLE', 'Client 360 Fixture', 0)");
        insertUser("ITC3601", "itc360.asha", "Asha Rao");
        insertUser("ITC3602", "itc360.bhavna", "Bhavna Iyer");
        insertUser("ITC3603", "itc360.chandan", "Chandan Gupta");
        insertProject("ITC3A", "IT Client 360 Alpha");
        insertProject("ITC3B", "IT Client 360 Beta");
        jdbc.update("INSERT INTO clients (client_code, name) VALUES ('ITC360', 'IT Client 360 Fixture')");

        clientId = id("SELECT id FROM clients WHERE client_code = ?", "ITC360");
        alpha = id("SELECT id FROM projects WHERE project_code = ?", "ITC3A");
        beta = id("SELECT id FROM projects WHERE project_code = ?", "ITC3B");
        asha = id("SELECT id FROM users WHERE username = ?", "itc360.asha");
        bhavna = id("SELECT id FROM users WHERE username = ?", "itc360.bhavna");
        chandan = id("SELECT id FROM users WHERE username = ?", "itc360.chandan");

        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        insertTicket("ITC360-1", alpha, asha, "CLOSED", now.minus(10, ChronoUnit.DAYS),
                now.minus(5, ChronoUnit.DAYS), now.minus(6, ChronoUnit.DAYS));
        insertTicket("ITC360-2", alpha, bhavna, "CLOSED", now.minus(10, ChronoUnit.DAYS),
                now.minus(5, ChronoUnit.DAYS), now.minus(3, ChronoUnit.DAYS));
        insertTicket("ITC360-3", beta, asha, "NEW", now.minus(2, ChronoUnit.DAYS), null, null);
        insertTicket("ITC360-4", beta, bhavna, "IN_PROGRESS", now.minus(2, ChronoUnit.DAYS), null, null);
        insertTicket("ITC360-5", alpha, null, "CLOSED", now.minus(9, ChronoUnit.DAYS), null,
                now.minus(8, ChronoUnit.DAYS));
        insertTicket("ITC360-6", alpha, chandan, "NEW", now.minus(1, ChronoUnit.DAYS), null, null);
    }

    @AfterEach
    void clearFixtureRows() {
        jdbc.update("DELETE FROM tickets WHERE ticket_code LIKE 'ITC360-%'");
        jdbc.update("DELETE FROM clients WHERE client_code = 'ITC360'");
        jdbc.update("DELETE FROM projects WHERE project_code IN ('ITC3A', 'ITC3B')");
        jdbc.update("DELETE FROM users WHERE username LIKE 'itc360.%'");
        jdbc.update("DELETE FROM roles WHERE code = 'ITC360_ROLE'");
    }

    @Test
    @DisplayName("no such client is empty, not a 404 dressed up as data")
    void noSuchClientIsEmpty() {
        assertThat(service.view(admin(), 9_999_999L, null, null, null)).isEmpty();
    }

    @Test
    @DisplayName("Admin sees every ticket against the client, including the unassigned one")
    void adminSeesEverything() {
        Client360Dtos.Client360Response view = view(admin());

        assertThat(view.data().tickets()).extracting(t -> t.ticketCode())
                .containsExactlyInAnyOrder("ITC360-1", "ITC360-2", "ITC360-3", "ITC360-4", "ITC360-5", "ITC360-6");
        assertThat(view.data().openCount()).isEqualTo(3); // -3, -4, -6
        assertThat(view.data().closedCount()).isEqualTo(3); // -1, -2, -5
        // Committed: -1 and -2 only (planned close date set). Met: -1 only.
        assertThat(view.data().slaCompliancePct()).isEqualByComparingTo("50.0");
    }

    @Test
    @DisplayName("a project-scoped role's figures cover only their own projects' tickets")
    void pmSeesOnlyItsProjects() {
        Client360Dtos.Client360Response view = view(caller(500L, "PM", List.of(alpha)));

        assertThat(view.data().tickets()).extracting(t -> t.ticketCode())
                .containsExactlyInAnyOrder("ITC360-1", "ITC360-2", "ITC360-5", "ITC360-6");
        assertThat(view.data().openCount()).isEqualTo(1); // -6 only; -3/-4 are BETA
        assertThat(view.data().closedCount()).isEqualTo(3);
        assertThat(view.data().slaCompliancePct()).isEqualByComparingTo("50.0");
    }

    @Test
    @DisplayName("an assignee-scoped role's figures narrow the same way the list does")
    void developerSeesOnlyOwnTickets() {
        Client360Dtos.Client360Response view = view(caller(asha, "DEVELOPER", List.of()));

        assertThat(view.data().tickets()).extracting(t -> t.ticketCode())
                .containsExactlyInAnyOrder("ITC360-1", "ITC360-3");
        assertThat(view.data().openCount()).isEqualTo(1);
        assertThat(view.data().closedCount()).isEqualTo(1);
        // Asha's only closed ticket met its planned date.
        assertThat(view.data().slaCompliancePct()).isEqualByComparingTo("100.0");
        assertThat(view.data().avgResolutionHrs()).isNotNull();
    }

    @Test
    @DisplayName("nothing closed reads as null, never as 0%")
    void nullRatherThanZeroWhenNothingIsClosed() {
        Client360Dtos.Client360Response view = view(caller(chandan, "DEVELOPER", List.of()));

        assertThat(view.data().tickets()).extracting(t -> t.ticketCode()).containsExactly("ITC360-6");
        assertThat(view.data().openCount()).isEqualTo(1);
        assertThat(view.data().closedCount()).isZero();
        assertThat(view.data().slaCompliancePct()).isNull();
        assertThat(view.data().avgResolutionHrs()).isNull();
    }

    @Test
    @DisplayName("?status= narrows the list without narrowing the figures beside it")
    void statusFilterNarrowsTheListOnly() {
        Client360Dtos.Client360Response view = service.view(admin(), clientId, "CLOSED", null, null)
                .orElseThrow();

        assertThat(view.data().tickets()).extracting(t -> t.ticketCode())
                .containsExactlyInAnyOrder("ITC360-1", "ITC360-2", "ITC360-5");
        // Same figures as the unfiltered Admin case — the KPI numbers are not
        // the status-filtered list's own count.
        assertThat(view.data().openCount()).isEqualTo(3);
        assertThat(view.data().closedCount()).isEqualTo(3);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Client360Dtos.Client360Response view(Authentication caller) {
        return service.view(caller, clientId, null, null, null).orElseThrow();
    }

    private Authentication admin() {
        return caller(1L, "ADMIN", List.of());
    }

    private Authentication caller(long userId, String role, List<Long> projectIds) {
        return new UsernamePasswordAuthenticationToken(
                new DevPrincipal(userId, "itc360.fixture", "Fixture", role, projectIds, List.of()),
                null, List.of());
    }

    private void insertUser(String empCode, String username, String fullName) {
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                   role_id, timezone, is_active, must_change_password)
                VALUES (?, ?, ?, 'not-a-real-hash', ?,
                        (SELECT id FROM roles WHERE code = 'ITC360_ROLE'), 'Asia/Kolkata', 1, 0)
                """, empCode, username, username + "@edunext.test", fullName);
    }

    private void insertProject(String code, String name) {
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, ?, 'ACTIVE')", code, name);
    }

    private void insertTicket(String code, long projectId, Long assignedTo, String status,
                              Instant dateReported, Instant plannedCloseDate, Instant actualCloseDate) {
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, client_id, title, level, original_level,
                                     assigned_to, status, date_reported, planned_close_date, actual_close_date)
                VALUES (?, ?, ?, ?, 'MEDIUM', 'MEDIUM', ?, ?, ?, ?, ?)
                """, code, projectId, clientId, "Client 360 fixture " + code, assignedTo, status,
                dateReported, plannedCloseDate, actualCloseDate);
    }

    private long id(String sql, String key) {
        return Optional.ofNullable(jdbc.queryForObject(sql, Long.class, key))
                .orElseThrow(() -> new IllegalStateException("fixture not seeded: " + key));
    }
}
