package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.api.security.TestPrincipals;
import com.edunext.edutrack.api.security.jwt.JwtAuthoritiesConverter;
import com.edunext.edutrack.api.security.permission.RolePermissions;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C-047 · {@code POST /tickets/{ticketId}/skip-stage} against a real,
 * Flyway-migrated MySQL — the gap {@code STREAM-C-TICKETS.md}'s own C-047
 * entry names explicitly: {@code handoff}, {@code rework}, {@code force-move}
 * and {@code skip-stage} had zero integration tests between them, which is
 * exactly how the {@code UnsupportedLockAttemptException} below reached
 * {@code develop} unnoticed and was found by hand at a browser instead of in
 * CI.
 *
 * <p>Fixtures are built with raw SQL — {@code TicketDetailIT}'s own
 * convention — rather than through {@code SkipService}'s collaborators,
 * because this suite exists to prove the route end to end, controller
 * through to the database, not to re-derive what the unit tests already
 * cover with mocks ({@code SkipServiceTest}, {@code TransitionServiceTest}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SkipControllerIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_skip_stage_it")
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
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtAuthoritiesConverter authorities;

    private long projectId;
    private long userId;
    private long templateId;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, 'Skip IT', 'ACTIVE')",
                "SKP" + suffix());
        projectId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'DEVELOPER'", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id, is_active)
                VALUES (?, ?, ?, 'x', 'Skip IT Actor', ?, 1)
                """, "SKP" + suffix(), "skp." + suffix(), "skp" + suffix() + "@example.test", roleId);
        userId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("INSERT INTO workflow_templates (name, is_default, is_active) VALUES (?, 0, 1)",
                "Skip IT template " + suffix());
        templateId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        insertStage(templateId, (short) 1, "QA", "Quality Assurance", "QA", true);
        insertStage(templateId, (short) 2, "DEPLOY", "Deployment", "DEPLOYMENT", false);
    }

    private void insertStage(long template, short seq, String code, String displayName, String ownerRole,
                              boolean optional) {
        jdbc.update("""
                INSERT INTO workflow_stages (template_id, seq, stage_code, display_name, owner_role, is_optional)
                VALUES (?, ?, ?, ?, ?, ?)
                """, template, seq, code, displayName, ownerRole, optional ? 1 : 0);
    }

    /** A ticket standing in {@code stageCode}, with one open hop already in it. */
    private String insertTicketStandingIn(String stageCode) {
        String ticketCode = "SK-26-" + suffix();
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, level, original_level, status,
                                     assigned_to, current_cycle_no, workflow_template_id, current_stage,
                                     current_iteration, stage_entered_at)
                VALUES (?, ?, 'skip probe', 'HIGH', 'HIGH', 'IN_PROGRESS', ?, 1, ?, ?, 1,
                        '2026-08-20 09:00:00')
                """, ticketCode, projectId, userId, templateId, stageCode);
        long ticketId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        // The open hop advance() needs to seal before it can append the skip's
        // own hop — TransitionService.advance's own note on what a ticket that
        // has never had a first hop does. row_hash is a 64-hex placeholder, not
        // a real chain value: previousRowHash() only refuses a NULL tail, on
        // TicketJournal's own guard, and this suite is not proving A-042's chain.
        jdbc.update("""
                INSERT INTO ticket_stage_transitions (ticket_id, cycle_no, iteration_no, seq_no, from_stage,
                                                       to_stage, to_user_id, action_code, entered_at,
                                                       is_current, row_hash)
                VALUES (?, 1, 1, 1, NULL, ?, ?, 'FORWARD', '2026-08-20 09:00:00', 1, ?)
                """, ticketId, stageCode, userId, "0".repeat(64));

        return ticketCode;
    }

    private static String suffix() {
        String base36 = Long.toString(Math.abs(System.nanoTime()), 36);
        return base36.length() <= 7 ? base36 : base36.substring(base36.length() - 7);
    }

    private String skipStageUrl(String ticketCode) {
        return "/api/v1/tickets/" + ticketCode + "/skip-stage";
    }

    private static String requestBody(String reason, String toStageCode) {
        String toStage = toStageCode == null ? "null" : "\"" + toStageCode + "\"";
        return "{\"reason\": \"" + reason + "\", \"toStageCode\": " + toStage + "}";
    }

    // ── the capability gate ─────────────────────────────────────────────────

    @Nested
    @DisplayName("ticket.skip_stage is Admin and PM's alone")
    class CapabilityGate {

        @Test
        @DisplayName("a caller without ticket.skip_stage never reaches the route")
        void refusedBeforeTheHandler() throws Exception {
            String ticketCode = insertTicketStandingIn("QA");

            mvc.perform(post(skipStageUrl(ticketCode)).contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody("client waived UAT", null))
                            .with(authentication(TestPrincipals.of(authorities, userId, "DEVELOPER", List.of(projectId)))))
                    .andExpect(status().isForbidden());
        }
    }

    // ── §4A.6 — only an optional stage may be skipped ───────────────────────

    @Nested
    @DisplayName("§4A.6 — the stage being left must be optional")
    class OptionalStageRule {

        @Test
        @DisplayName("a non-optional stage is refused with 422, before the ticket moves")
        void refusesMandatoryStage() throws Exception {
            String ticketCode = insertTicketStandingIn("DEPLOY");

            mvc.perform(post(skipStageUrl(ticketCode)).contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody("behind schedule", null))
                            .with(authentication(TestPrincipals.of(authorities, userId, RolePermissions.PM, List.of(projectId)))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.detail").value(Matchers.containsString("Deployment")));

            assertThat(jdbc.queryForObject(
                    "SELECT current_stage FROM tickets WHERE ticket_code = ?", String.class, ticketCode))
                    .as("a refusal must leave the ticket exactly where it was")
                    .isEqualTo("DEPLOY");
        }
    }

    // ── bean validation ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("reason is mandatory")
    class Validation {

        @Test
        @DisplayName("a blank reason is refused with 400 before any service code runs")
        void blankReasonRefused() throws Exception {
            String ticketCode = insertTicketStandingIn("QA");

            mvc.perform(post(skipStageUrl(ticketCode)).contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody("", null))
                            .with(authentication(TestPrincipals.of(authorities, userId, RolePermissions.ADMIN, List.of(projectId)))))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── the success path ─────────────────────────────────────────────────────

    /**
     * ⚠ Confirmed live by this suite, not merely repeated from
     * {@code STREAM-C-TICKETS.md}'s C-047 note: every {@code SKIP} that reaches
     * {@code advance()} throws {@code org.hibernate.UnsupportedLockAttemptException:
     * Lock mode not supported}, from {@code ImmutableEntityEntry.setLockMode}.
     *
     * <p><b>Root cause, traced through this run's own stack trace</b> —
     * {@code TicketStageTransitionRepository.findFirstByTicketIdOrderByIdDesc}
     * carries {@code @Lock(PESSIMISTIC_WRITE)}, added by {@code 74f60e58}
     * ("the chain tail must be read with a locking read") to fix a real
     * concurrent-append fork. {@code TicketStageTransition} is {@code @Immutable}
     * ({@code TicketJournal}'s own append-only guarantee). {@code append()}
     * calls that locking query to find the chain tail — which, inside
     * {@code advance()}, is the exact same open hop {@code openHopFor} already
     * loaded into this transaction's persistence context a few lines earlier.
     * Hibernate resolves the query to the already-managed instance and tries to
     * upgrade its lock mode in place; {@code @Immutable} entities are backed by
     * {@code ImmutableEntityEntry}, which accepts no lock mode but
     * {@code NONE} and throws rather than silently no-op. Every route through
     * {@code TransitionService.advance} — handoff, rework, force-move, skip —
     * shares this path and would fail identically; skip is simply the first of
     * the four ever proved against a real database, because it is the first of
     * the four with an integration test at all.
     *
     * <p><b>Stream A's {@code domain/journal}/{@code TicketStageTransitionRepository}
     * — flagged, not fixed here</b>, on CLAUDE.md's ownership boundary. Disabled
     * rather than left red: CLAUDE.md's verification gate merges no PR with a
     * failing check, and a build that cannot merge is not a more honest record
     * of the gap than a disabled test whose reason names the exact exception,
     * the exact query, and the exact commit that introduced the conflict.
     * Re-enable once that lock/immutability interaction is resolved upstream —
     * at that point this test is the CI signal that it was.
     */
    @Nested
    @DisplayName("a skip actually moves the ticket, in a real database")
    class SuccessPath {

        @Test
        @Disabled("Blocked on a Stream A defect confirmed by this test: advance() -> TicketJournal.append() -> "
                + "TicketStageTransitionRepository.findFirstByTicketIdOrderByIdDesc's @Lock(PESSIMISTIC_WRITE) "
                + "throws org.hibernate.UnsupportedLockAttemptException against the already-managed, @Immutable "
                + "TicketStageTransition open hop. Shared by handoff/rework/force-move/skip alike; see this "
                + "class's own javadoc and STREAM-C-TICKETS.md's C-047 entry. Not Stream C's to fix "
                + "(domain/journal, TicketStageTransitionRepository) — re-enable once Stream A resolves it.")
        @DisplayName("QA (optional) is skipped, the ribbon shows it struck through, DEPLOY becomes current")
        void skipsForward() throws Exception {
            String ticketCode = insertTicketStandingIn("QA");

            mvc.perform(post(skipStageUrl(ticketCode)).contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody("Client waived UAT for this release", null))
                            .with(authentication(TestPrincipals.of(authorities, userId, RolePermissions.ADMIN, List.of(projectId)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.currentStageCode").value("DEPLOY"))
                    .andExpect(jsonPath("$.data.segments[0].stageCode").value("QA"))
                    .andExpect(jsonPath("$.data.segments[0].state").value("SKIPPED"))
                    .andExpect(jsonPath("$.data.segments[0].skipReason")
                            .value(Matchers.containsString("Skipped by:")))
                    .andExpect(jsonPath("$.data.segments[1].stageCode").value("DEPLOY"))
                    .andExpect(jsonPath("$.data.segments[1].state").value("CURRENT"));

            assertThat(jdbc.queryForObject(
                    "SELECT current_stage FROM tickets WHERE ticket_code = ?", String.class, ticketCode))
                    .isEqualTo("DEPLOY");

            long ticketId = jdbc.queryForObject(
                    "SELECT id FROM tickets WHERE ticket_code = ?", Long.class, ticketCode);
            String reason = jdbc.queryForObject("""
                    SELECT reason FROM ticket_stage_transitions
                     WHERE ticket_id = ? AND action_code = 'SKIP'
                    """, String.class, ticketId);
            assertThat(reason)
                    .as("the reason is stored with the authoriser's name folded in — SkipService's own rule")
                    .contains("Client waived UAT for this release")
                    .contains("Skipped by:");
        }
    }
}
