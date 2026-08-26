package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.api.security.TestPrincipals;
import com.edunext.edutrack.api.security.jwt.JwtAuthoritiesConverter;
import com.edunext.edutrack.api.security.permission.RolePermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C-062 · {@code GET /stages/queue} against a real, Flyway-migrated MySQL.
 *
 * <p>What this suite exists to prove is {@link StageQueueScope}'s whole
 * reason for existing rather than {@code ScopeResolver}'s: a Developer/QA/
 * Deployment resource on a project must see a stage's <em>entire</em> queue,
 * not only tickets already assigned to them, and a resource off the project
 * must see none of it. {@code PermissionMatrixTest} already proves every role
 * may reach the route at all; this proves what comes back once they do.
 *
 * <p>Project membership is read from the caller's own token
 * ({@code TestPrincipals}' {@code projects} claim) rather than from a
 * {@code project_members} row — {@link StageQueueScope}'s own javadoc states
 * why a REST read uses A-034's usual source instead of the DB check
 * {@code StageQueueSubscriptionScope} needs for a long-lived subscription —
 * so no membership table is seeded here at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class StageQueueControllerIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_stage_queue_it")
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

    private long projectA;
    private long projectB;
    private long templateId;
    private long resourceId;

    @BeforeEach
    void seed() {
        projectA = insertProject("QIA");
        projectB = insertProject("QIB");
        resourceId = insertUser("QA");

        jdbc.update("INSERT INTO workflow_templates (name, is_default, is_active) VALUES (?, 0, 1)",
                "Stage Queue IT template " + suffix());
        templateId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        // sla_hours tiny enough that any nonzero working time over 30 days
        // breaches it, and large enough (999h) that "just now" never does —
        // see insertTicket's own note on why wall-clock age is used instead of
        // a fixed duration, which would be sensitive to the day this runs on.
        jdbc.update("""
                INSERT INTO workflow_stages (template_id, seq, stage_code, display_name, owner_role,
                                              is_optional, sla_hours)
                VALUES (?, 1, 'QA', 'Quality Assurance', 'QA', 0, 0.01)
                """, templateId);
    }

    private long insertProject(String prefix) {
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, ?, 'ACTIVE')",
                prefix + suffix(), "Stage Queue IT " + prefix);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertUser(String roleCode) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", Long.class, roleCode);
        String tag = "sq_" + suffix();
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id, is_active)
                VALUES (?, ?, ?, 'x', 'Stage Queue IT', ?, 1)
                """, tag, tag, tag + "@example.test", roleId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /**
     * A ticket standing in {@code QA} on {@code projectId}.
     *
     * <p>{@code ageDays} ago rather than an exact {@code sla_hours}-relative
     * offset, so the breach assertions below do not depend on which day of the
     * week this suite happens to run: {@code sla_hours = 0.01} means any
     * nonzero working time breaches it, and thirty days of wall clock contains
     * some unless the working calendar itself has zero working days, which
     * {@code ck_working_calendar_weekly_off} already refuses to allow.
     */
    private String insertTicket(long projectId, String status, Long assignedTo, int ageDays) {
        String ticketCode = "SQ-26-" + suffix();
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, level, original_level, status,
                                     assigned_to, current_cycle_no, workflow_template_id, current_stage,
                                     current_iteration, stage_entered_at)
                VALUES (?, ?, 'stage queue probe', 'MEDIUM', 'MEDIUM', ?, ?, 1, ?, 'QA', 1,
                        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL ? DAY))
                """, ticketCode, projectId, status, assignedTo, templateId, ageDays);
        return ticketCode;
    }

    private static String suffix() {
        String base36 = Long.toString(Math.abs(System.nanoTime()), 36);
        return base36.length() <= 7 ? base36 : base36.substring(base36.length() - 7);
    }

    private static String queueUrl(String stage, Long projectId, Boolean unassignedOnly) {
        StringBuilder url = new StringBuilder("/api/v1/stages/queue?stage=").append(stage);
        if (projectId != null) {
            url.append("&projectId=").append(projectId);
        }
        if (unassignedOnly != null) {
            url.append("&unassignedOnly=").append(unassignedOnly);
        }
        return url.toString();
    }

    // ── project membership is the scope, not assigned_to = me ────────────────

    @Nested
    @DisplayName("visibility is project membership, never assigned_to = me")
    class ProjectMembershipScope {

        @Test
        @DisplayName("a QA resource on the project sees a ticket nobody assigned to them")
        void seesTheWholeProjectsQueue() throws Exception {
            String ticket = insertTicket(projectA, "IN_PROGRESS", null, 0);

            mvc.perform(get(queueUrl("QA", null, null))
                            .with(authentication(TestPrincipals.of(
                                    authorities, resourceId, RolePermissions.QA, List.of(projectA)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].ticket.ticketId").value(org.hamcrest.Matchers.hasItem(ticket)));
        }

        @Test
        @DisplayName("a QA resource off the project sees none of its queue")
        void seesNothingFromAProjectTheyAreNotOn() throws Exception {
            insertTicket(projectA, "IN_PROGRESS", null, 0);

            mvc.perform(get(queueUrl("QA", null, null))
                            .with(authentication(TestPrincipals.of(
                                    authorities, resourceId, RolePermissions.QA, List.of(projectB)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }

        @Test
        @DisplayName("Admin sees every project's queue with no membership at all")
        void adminSeesEveryProject() throws Exception {
            String ticketA = insertTicket(projectA, "IN_PROGRESS", null, 0);
            String ticketB = insertTicket(projectB, "IN_PROGRESS", null, 0);

            mvc.perform(get(queueUrl("QA", null, null))
                            .with(authentication(TestPrincipals.of(
                                    authorities, resourceId, RolePermissions.ADMIN, List.of()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].ticket.ticketId", org.hamcrest.Matchers.hasItems(ticketA, ticketB)));
        }

        @Test
        @DisplayName("naming a project the caller is not on narrows to nothing, never widens")
        void explicitProjectIdCannotWidenScope() throws Exception {
            insertTicket(projectB, "IN_PROGRESS", null, 0);

            mvc.perform(get(queueUrl("QA", projectB, null))
                            .with(authentication(TestPrincipals.of(
                                    authorities, resourceId, RolePermissions.QA, List.of(projectA)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }
    }

    // ── a queue is work waiting, not an archive ───────────────────────────────

    @Nested
    @DisplayName("a closed ticket is not waiting for anybody")
    class ClosedExclusion {

        @Test
        @DisplayName("CLOSED never appears, even standing in the filtered stage")
        void closedTicketsAreExcluded() throws Exception {
            insertTicket(projectA, "CLOSED", null, 0);
            String open = insertTicket(projectA, "IN_PROGRESS", null, 0);

            mvc.perform(get(queueUrl("QA", null, null))
                            .with(authentication(TestPrincipals.of(
                                    authorities, resourceId, RolePermissions.QA, List.of(projectA)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(1)))
                    .andExpect(jsonPath("$.data[0].ticket.ticketId").value(open));
        }
    }

    // ── unassignedOnly ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("unassignedOnly narrows within the caller's own scope")
    class UnassignedOnly {

        @Test
        @DisplayName("only rows with no assignee come back")
        void narrowsToUnassigned() throws Exception {
            String unassigned = insertTicket(projectA, "IN_PROGRESS", null, 0);
            insertTicket(projectA, "IN_PROGRESS", resourceId, 0);

            mvc.perform(get(queueUrl("QA", null, true))
                            .with(authentication(TestPrincipals.of(
                                    authorities, resourceId, RolePermissions.QA, List.of(projectA)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(1)))
                    .andExpect(jsonPath("$.data[0].ticket.ticketId").value(unassigned));
        }
    }

    // ── the SLA breach cue is working time, computed server-side ─────────────

    @Nested
    @DisplayName("stageSlaBreached is computed from working time against the stage's own SLA")
    class BreachFlag {

        @Test
        @DisplayName("thirty days in a 0.01h-SLA stage reports breached")
        void oldEnoughToBreach() throws Exception {
            String ticket = insertTicket(projectA, "IN_PROGRESS", null, 30);

            mvc.perform(get(queueUrl("QA", null, null))
                            .with(authentication(TestPrincipals.of(
                                    authorities, resourceId, RolePermissions.QA, List.of(projectA)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].ticket.ticketId").value(ticket))
                    .andExpect(jsonPath("$.data[0].stageSlaBreached").value(true));
        }
    }

    // ── the sort is oldest first, server-side ─────────────────────────────────

    @Nested
    @DisplayName("rows come back oldest-waiting first")
    class Ordering {

        @Test
        @DisplayName("a ticket that entered the stage earlier sorts before one that entered later")
        void oldestFirst() throws Exception {
            String newer = insertTicket(projectA, "IN_PROGRESS", null, 1);
            String older = insertTicket(projectA, "IN_PROGRESS", null, 5);

            mvc.perform(get(queueUrl("QA", null, null))
                            .with(authentication(TestPrincipals.of(
                                    authorities, resourceId, RolePermissions.QA, List.of(projectA)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].ticket.ticketId").value(older))
                    .andExpect(jsonPath("$.data[1].ticket.ticketId").value(newer));
        }
    }
}
