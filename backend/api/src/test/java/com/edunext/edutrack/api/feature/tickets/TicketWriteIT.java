package com.edunext.edutrack.api.feature.tickets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import com.edunext.edutrack.api.security.dev.DevPrincipal;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C-067 · the create and patch paths, against real MySQL.
 *
 * <p>An integration test because every guarantee worth asserting here is about
 * what reaches the database: that the sanitiser ran before the insert, that one
 * history row exists per field that actually changed, and that the two write
 * gates refuse before an ID is burnt.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles({"local", "dev-noauth"})
class TicketWriteIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_it")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+00:00", "--log-bin-trust-function-creators=1")
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
    TicketWriteService service;

    @Autowired
    JdbcTemplate jdbc;

    private long projectId;
    private long adminId;

    /**
     * An Admin, so the scope guard lets the patch through to the assertion the
     * test is actually making. `null` is fine for create — it does not scope,
     * and its @UnscopedAccess note says why — but patch goes through
     * ScopedTickets.requireByCode and answers 404 to a caller it cannot resolve,
     * which is the guard working rather than a test problem.
     */
    private Authentication admin() {
        return new UsernamePasswordAuthenticationToken(
                new DevPrincipal(adminId, "it_w_admin", "IT Admin", "ADMIN", List.of(), List.of()), null, List.of());
    }

    @BeforeEach
    void seed() {
        jdbc.update("INSERT IGNORE INTO projects (project_code, name) VALUES ('ITW', 'Write IT')");
        projectId = jdbc.queryForObject("SELECT id FROM projects WHERE project_code = 'ITW'", Long.class);

        // A real row: ticket_history.actor_id carries an FK to users, so a
        // principal invented out of thin air fails the insert rather than the
        // assertion — which is the constraint doing its job.
        jdbc.update("""
                INSERT IGNORE INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES ('ITW-1', 'it_w_admin', 'it_w_admin@it.example', 'x', 'IT Admin',
                        (SELECT id FROM roles WHERE code = 'ADMIN'))""");
        adminId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'it_w_admin'", Long.class);
    }

    private TicketCreateDtos.CreateRequest request(Integer moduleId, String steps) {
        return new TicketCreateDtos.CreateRequest(projectId, "A ticket worth raising", null, 1,
                moduleId, null, null, steps, "MEDIUM", null, null, null, null, List.of(), null, null, false);
    }

    /**
     * 🔴 PLAN.md §3.9: "the only sanitiser an attacker cannot skip is the one on
     * the write path". Asserted against the stored column, not the response, so
     * a sanitiser that ran on the way out would still fail this.
     */
    @Test
    @DisplayName("rich text is sanitised before it is stored, not on the way out")
    void richTextIsSanitisedOnWrite() {
        var created = service.create(null, request(null,
                "<p>Open the receipt</p><script>alert(1)</script><iframe src=\"evil\"></iframe>"));

        String stored = jdbc.queryForObject(
                "SELECT steps_to_generate FROM tickets WHERE ticket_code = ?", String.class, created.ticketCode());

        assertThat(stored).isEqualTo("<p>Open the receipt</p>");
        assertThat(stored).doesNotContain("script", "iframe");
    }

    @Test
    @DisplayName("the id comes from the project sequence, so two creates never collide")
    void idsComeFromTheSequence() {
        var first = service.create(null, request(null, null));
        var second = service.create(null, request(null, null));
        assertThat(first.ticketCode()).isNotEqualTo(second.ticketCode());
        assertThat(first.ticketCode()).startsWith("ITW-");
    }

    @Test
    @DisplayName("a create writes exactly one CREATED history row")
    void createWritesItsHistoryRow() {
        var created = service.create(null, request(null, null));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT h.event_type FROM ticket_history h
                  JOIN tickets t ON t.id = h.ticket_id
                 WHERE t.ticket_code = ?""", created.ticketCode());
        assertThat(rows).singleElement().extracting(r -> r.get("event_type")).isEqualTo("CREATED");
    }

    /**
     * One row per field that CHANGED, not per field named. A history that
     * recorded {@code screenName: "Login" → "Login"} is noise in the one place
     * noise is most expensive.
     */
    @Test
    @DisplayName("a patch writes one FIELD_CHANGED row per field that actually changed")
    void patchWritesOneRowPerRealChange() {
        var created = service.create(null, request(null, null));

        service.patch(admin(), created.ticketCode(), new TicketCreateDtos.PatchRequest(
                "A ticket worth raising",   // unchanged — must write nothing
                null, null, "Fee Receipt", "PDF export", null, null));

        List<String> fields = jdbc.queryForList("""
                SELECT h.field_name FROM ticket_history h
                  JOIN tickets t ON t.id = h.ticket_id
                 WHERE t.ticket_code = ? AND h.event_type = 'FIELD_CHANGED'
                 ORDER BY h.id""", String.class, created.ticketCode());

        assertThat(fields).containsExactly("screenName", "feature");
    }

    @Test
    @DisplayName("a deactivated module is refused on write and stays readable on old tickets")
    void deactivatedModulesAreRefusedOnWrite() {
        Integer library = jdbc.queryForObject(
                "SELECT id FROM product_modules WHERE code = 'LIBRARY'", Integer.class);

        // Raised while it was still offered.
        var old = service.create(null, request(library, null));

        jdbc.update("UPDATE product_modules SET is_active = 0 WHERE id = ?", library);
        try {
            assertThatThrownBy(() -> service.create(null, request(library, null)))
                    .isInstanceOf(UnknownModuleException.class)
                    .hasMessageContaining("no longer offered");

            // The old ticket keeps it — filtering it out of the read is what
            // would leave that cell blank.
            assertThat(jdbc.queryForObject("SELECT module_id FROM tickets WHERE ticket_code = ?",
                    Integer.class, old.ticketCode())).isEqualTo(library);
        } finally {
            jdbc.update("UPDATE product_modules SET is_active = 1 WHERE id = ?", library);
        }
    }

    @Test
    @DisplayName("isClientRaised is derived from the pair, never taken from the caller")
    void clientRaisedIsDerived() {
        // §4B.2. The request says true; only naming both a client and a contact
        // makes it true, and this one names neither.
        var created = service.create(null, new TicketCreateDtos.CreateRequest(
                projectId, "Says it is client raised", null, 1, null, null, null, null, "MEDIUM",
                null, null, /* isClientRaised */ true, null, List.of(), null, null, false));

        assertThat(jdbc.queryForObject("SELECT is_client_raised FROM tickets WHERE ticket_code = ?",
                Boolean.class, created.ticketCode())).isFalse();
    }
}
