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
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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

    // ------------------------------------------------------------------
    // C-071 · B-019's project settings, obeyed
    // ------------------------------------------------------------------

    /**
     * The other tests in this class are the other half of this one: {@code ITW}
     * has no {@code project_task_types} rows and every one of them raises a
     * ticket successfully. An empty allow-list allowing nothing is the failure
     * that would take out ticket creation on every project at once, so it is
     * asserted by the whole file rather than by one case.
     */
    @Test
    @DisplayName("a project that restricts its task types refuses one outside the list")
    void allowListIsEnforced() {
        Integer allowed = jdbc.queryForObject("SELECT id FROM task_types ORDER BY id LIMIT 1", Integer.class);
        Integer refused = jdbc.queryForObject(
                "SELECT id FROM task_types WHERE id <> ? ORDER BY id LIMIT 1", Integer.class, allowed);
        long before = ticketSeq();

        jdbc.update("INSERT INTO project_task_types (project_id, task_type_id) VALUES (?, ?)",
                projectId, allowed);
        try {
            assertThatThrownBy(() -> service.create(null, request(refused)))
                    .isInstanceOf(TaskTypeNotAllowedException.class);

            // Refused before the sequence moved. A number burnt on a rejected
            // request is a permanent gap in that project's ticket codes.
            assertThat(ticketSeq()).isEqualTo(before);

            // And the allowed one still goes through, so this is a restriction
            // rather than a project nobody can raise a ticket on.
            assertThat(service.create(null, request(allowed)).ticketCode()).startsWith("ITW-");
        } finally {
            jdbc.update("DELETE FROM project_task_types WHERE project_id = ?", projectId);
        }
    }

    @Test
    @DisplayName("every mandatory field the ticket leaves empty is reported, in one refusal")
    void mandatoryFieldsAreEnforced() {
        long before = ticketSeq();
        jdbc.update("UPDATE projects SET mandatory_fields = ? WHERE id = ?",
                "[\"MODULE\", \"ESTIMATED_HRS\"]", projectId);
        try {
            MandatoryFieldsMissingException refusal = catchThrowableOfType(
                    MandatoryFieldsMissingException.class, () -> service.create(null, request(null, null)));

            // Both, in one refusal. One per round trip on a form with ten
            // optional fields is a conversation rather than an error message.
            assertThat(refusal.missing()).containsOnlyKeys("moduleId", "estimatedHrs");
            assertThat(ticketSeq()).isEqualTo(before);
        } finally {
            jdbc.update("UPDATE projects SET mandatory_fields = NULL WHERE id = ?", projectId);
        }
    }

    /**
     * The two things a settings read has to collapse to the same answer: a
     * project that predates B-019 holds {@code NULL}, and one whose last box was
     * unticked holds {@code NULL} too. Neither may require anything.
     */
    @Test
    @DisplayName("an unconfigured project requires nothing beyond what every ticket requires")
    void noMandatoryFieldsMeansNoExtraRules() {
        assertThat(jdbc.queryForObject("SELECT mandatory_fields FROM projects WHERE id = ?",
                String.class, projectId)).isNull();

        assertThat(service.create(null, request(null, null)).ticketCode()).startsWith("ITW-");
    }

    /**
     * PLANNED_CLOSE_DATE is measured against the date the ticket would carry, not
     * against what the caller sent — {@code ProjectTicketRules} carries the
     * argument. This is the half that can be asserted without an SLA policy to
     * arrange: an explicit override satisfies it.
     */
    @Test
    @DisplayName("a required planned close date is satisfied by the date the ticket ends up with")
    void plannedCloseDateIsJudgedOnTheResolvedValue() {
        jdbc.update("UPDATE projects SET mandatory_fields = ? WHERE id = ?",
                "[\"PLANNED_CLOSE_DATE\"]", projectId);
        try {
            var created = service.create(null, new TicketCreateDtos.CreateRequest(
                    projectId, "Has a target date", null, 1, null, null, null, null, "MEDIUM",
                    null, null, null, null, List.of(), null,
                    java.time.Instant.parse("2026-09-30T09:00:00Z"), false));

            assertThat(created.ticketCode()).startsWith("ITW-");
        } finally {
            jdbc.update("UPDATE projects SET mandatory_fields = NULL WHERE id = ?", projectId);
        }
    }

    private TicketCreateDtos.CreateRequest request(Integer taskTypeId) {
        return new TicketCreateDtos.CreateRequest(projectId, "A ticket worth raising", null, taskTypeId,
                null, null, null, null, "MEDIUM", null, null, null, null, List.of(), null, null, false);
    }

    private long ticketSeq() {
        return jdbc.queryForObject("SELECT ticket_seq FROM projects WHERE id = ?", Long.class, projectId);
    }

    // ------------------------------------------------------------------
    // workflow_template_id — resolved on create, wired into ticket creation
    // ------------------------------------------------------------------

    /**
     * The bug this closes: {@code create()} never called {@code TemplateResolver}
     * — {@code TemplateResolver}'s own javadoc says as much — so every ticket was
     * born with {@code workflow_template_id = NULL} and the ribbon
     * {@code RibbonAssembler} now assembles on the detail page had nothing to
     * show. {@code ITW} has no project-specific mapping, so this resolves
     * through rung 5 to B-004's seeded org default, "Standard Dev Flow", whose
     * first stage by {@code seq} is {@code INTAKE} (V20260807_1700).
     */
    @Test
    @DisplayName("create resolves and stores a workflow template, and starts the ticket on its first stage")
    void createResolvesWorkflowTemplate() {
        var created = service.create(null, request(null, null));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT workflow_template_id, current_stage FROM tickets WHERE ticket_code = ?",
                created.ticketCode());

        Long templateId = jdbc.queryForObject(
                "SELECT id FROM workflow_templates WHERE name = 'Standard Dev Flow'", Long.class);

        assertThat(row.get("workflow_template_id")).isEqualTo(templateId);
        assertThat(row.get("current_stage")).isEqualTo("INTAKE");
    }

    /**
     * The other half of {@link #createResolvesWorkflowTemplate}: setting
     * {@code current_stage} is not enough on its own for the ribbon or
     * Handoff to have anything to act on — {@code TransitionService.advance}
     * refuses without an open {@code ticket_stage_transitions} row
     * ({@code NoOpenStageException}), and {@code RibbonAssembler} renders
     * every segment {@code PENDING} without one, exactly what a ticket
     * created before this fix looked like. Asserted against the stored rows
     * rather than the response, on {@link #richTextIsSanitisedOnWrite}'s own
     * reasoning for the same choice.
     */
    @Test
    @DisplayName("create opens cycle 1 and the genesis hop, so the ribbon has a CURRENT segment from the start")
    void createOpensTheFirstCycleAndHop() {
        var created = service.create(null, request(null, null));
        long ticketId = jdbc.queryForObject(
                "SELECT id FROM tickets WHERE ticket_code = ?", Long.class, created.ticketCode());

        Map<String, Object> cycle = jdbc.queryForMap(
                "SELECT cycle_no, is_sealed FROM ticket_cycles WHERE ticket_id = ?", ticketId);
        assertThat(cycle.get("cycle_no")).isEqualTo(1);
        assertThat(cycle.get("is_sealed")).isEqualTo(false);

        Map<String, Object> hop = jdbc.queryForMap(
                "SELECT cycle_no, iteration_no, seq_no, from_stage, to_stage, action_code, "
                        + "exited_at, is_current, row_hash FROM ticket_stage_transitions WHERE ticket_id = ?",
                ticketId);
        assertThat(hop.get("cycle_no")).isEqualTo(1);
        assertThat(hop.get("iteration_no")).isEqualTo(1);
        assertThat(hop.get("seq_no")).isEqualTo(1);
        assertThat(hop.get("from_stage")).isNull();
        assertThat(hop.get("to_stage")).isEqualTo("INTAKE");
        assertThat(hop.get("action_code")).isEqualTo("FORWARD");
        assertThat(hop.get("exited_at")).isNull();
        assertThat(hop.get("is_current")).isEqualTo(true);
        assertThat(hop.get("row_hash")).isNotNull();
    }

    /** An unassigned ticket still gets a genesis hop — {@code to_user_id} null,
     * on {@code TransitionService.resolveAssignee}'s identical precedent for a
     * later hop whose receiving role has nobody active. */
    @Test
    @DisplayName("create opens the genesis hop for an unassigned ticket too")
    void createOpensTheFirstHopEvenUnassigned() {
        var created = service.create(null, request(null, null));

        Long toUserId = jdbc.queryForObject(
                "SELECT t.to_user_id FROM ticket_stage_transitions t JOIN tickets k ON k.id = t.ticket_id "
                        + "WHERE k.ticket_code = ?", Long.class, created.ticketCode());
        assertThat(toUserId).isNull();
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
