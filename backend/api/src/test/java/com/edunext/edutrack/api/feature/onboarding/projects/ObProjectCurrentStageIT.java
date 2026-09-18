package com.edunext.edutrack.api.feature.onboarding.projects;

import com.edunext.edutrack.api.feature.onboarding.projects.ObProjectReadRepository.StageRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ObProjectReadRepository#stagesByProject} against real MySQL —
 * specifically {@code STAGE_ROLLUP}'s {@code minActiveSequence}, which the
 * Projects grid's "Current step" column and the project header's
 * {@code isCurrent} flag both read straight from.
 *
 * <h2>Why this needed a container, not a fixture on {@link StageRow}</h2>
 *
 * <p>{@code ObProjectStageFoldTest} proves {@code foldToProject} sums rows
 * correctly; it cannot prove the rows are correct in the first place, because
 * it builds them by hand. Whether a {@code PENDING} task with an answered
 * check list item is "running" is answered inside the SQL's {@code CASE}, so
 * only a real database can prove it.
 *
 * <h2>The bug this pins</h2>
 *
 * <p>{@code answerItem} lets an implementor tick a check list item without the
 * step ever moving off {@code PENDING} — see its own javadoc — and
 * {@code moduleStripStats.ts}' {@code taskProgress} has always read that as
 * "partial", the same as a live {@code IN_PROGRESS}. Before this fix,
 * {@code minActiveSequence} only looked at status, so a project with real,
 * visible progress on its module strip reported no current stage at all on
 * the grid — indistinguishable from a project nobody had touched.
 */
@SpringBootTest
@Testcontainers
class ObProjectCurrentStageIT {

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
    ObProjectReadRepository reads;

    @Autowired
    JdbcTemplate jdbc;

    private long owner;
    private long product;

    @BeforeEach
    void seed() {
        /*
          Order follows the foreign keys down — see ObProjectBoardIT's own
          note. Items cascade off steps, but are listed anyway so this file
          does not depend on that behaviour to stay clean between tests.
        */
        jdbc.update("DELETE FROM ob_journey_step_items");
        jdbc.update("DELETE FROM ob_journey_steps");
        jdbc.update("DELETE FROM ob_journeys");
        jdbc.update("DELETE FROM ob_projects");
        jdbc.update("DELETE FROM ob_journey_template_steps WHERE template_id IN "
                + "(SELECT id FROM ob_journey_templates WHERE name LIKE 'it_obcurstage_%')");
        jdbc.update("DELETE FROM ob_journey_template_stages WHERE template_id IN "
                + "(SELECT id FROM ob_journey_templates WHERE name LIKE 'it_obcurstage_%')");
        jdbc.update("DELETE FROM ob_journey_templates WHERE name LIKE 'it_obcurstage_%'");
        jdbc.update("DELETE FROM ob_client_applications WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'it_obcurstage_%')");
        jdbc.update("DELETE FROM ob_clients WHERE name LIKE 'it_obcurstage_%'");
        jdbc.update("DELETE FROM ob_products WHERE code LIKE 'IT_OBCURSTAGE_%'");
        jdbc.update("DELETE FROM users WHERE username LIKE 'it_obcurstage_%'");

        owner = insertUser("it_obcurstage_owner");
        product = insertProduct("IT_OBCURSTAGE_ERP", "ERP");
    }

    @Test
    @DisplayName("a PENDING task with an answered check list item is the project's current stage")
    void aPendingTaskWithAnAnsweredItemIsCurrent() {
        long template = insertTemplate();
        long stage = insertTemplateStage(template, "Configuration", 1);
        long templateStep = insertTemplateStep(template, stage, 1, "School Configuration");

        long client = insertClient("it_obcurstage_ticked");
        long project = insertProject(client);
        long journey = insertJourney(project, client, template);
        long step = insertStep(journey, templateStep, 1, "School Configuration");
        // Ticked through, but never formally started — answerItem never
        // requires the step to be IN_PROGRESS.
        insertItem(step, 1, "Source data received", true);

        StageRow row = onlyStage(project);

        assertThat(row.stageName()).isEqualTo("Configuration");
        assertThat(row.minActiveSequence()).isNotNull();
    }

    @Test
    @DisplayName("a PENDING task with nothing answered is not current")
    void aPendingTaskWithNothingAnsweredIsNotCurrent() {
        long template = insertTemplate();
        long stage = insertTemplateStage(template, "Configuration", 1);
        long templateStep = insertTemplateStep(template, stage, 1, "School Configuration");

        long client = insertClient("it_obcurstage_untouched");
        long project = insertProject(client);
        long journey = insertJourney(project, client, template);
        long step = insertStep(journey, templateStep, 1, "School Configuration");
        insertItem(step, 1, "Source data received", null);

        StageRow row = onlyStage(project);

        assertThat(row.minActiveSequence()).isNull();
    }

    @Test
    @DisplayName("a live IN_PROGRESS task is still current, item or no item")
    void inProgressStillWorksWithoutAnyItem() {
        long template = insertTemplate();
        long stage = insertTemplateStage(template, "Configuration", 1);
        long templateStep = insertTemplateStep(template, stage, 1, "School Configuration");

        long client = insertClient("it_obcurstage_live");
        long project = insertProject(client);
        long journey = insertJourney(project, client, template);
        insertStepWithStatus(journey, templateStep, 1, "School Configuration", "IN_PROGRESS");

        StageRow row = onlyStage(project);

        assertThat(row.minActiveSequence()).isNotNull();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private StageRow onlyStage(long projectId) {
        List<StageRow> rows = reads.stagesByProject(List.of(projectId)).get(projectId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private long insertUser(String username) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles ORDER BY id LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?)
                """, username, username, username + "@example.com", username, roleId);
        return lastId();
    }

    private long insertProduct(String code, String name) {
        jdbc.update("INSERT INTO ob_products (code, name) VALUES (?, ?)", code, name);
        return lastId();
    }

    private long insertTemplate() {
        jdbc.update("""
                INSERT INTO ob_journey_templates (product_id, name, version, is_active)
                VALUES (?, ?, 1, 1)
                """, product, "it_obcurstage_template_" + System.nanoTime());
        return lastId();
    }

    private long insertTemplateStage(long templateId, String name, int sequence) {
        jdbc.update("""
                INSERT INTO ob_journey_template_stages (template_id, implementation_stage_id, name, sequence)
                VALUES (?, NULL, ?, ?)
                """, templateId, name, sequence);
        return lastId();
    }

    private long insertTemplateStep(long templateId, long templateStageId, int sequence, String name) {
        jdbc.update("""
                INSERT INTO ob_journey_template_steps (template_id, template_stage_id, sequence, name)
                VALUES (?, ?, ?, ?)
                """, templateId, templateStageId, sequence, name);
        return lastId();
    }

    private long insertClient(String name) {
        jdbc.update("""
                INSERT INTO ob_clients (name, onboarding_date, overall_status, created_by)
                VALUES (?, '2026-03-01', 'ONBOARDING', ?)
                """, name, owner);
        long clientId = lastId();
        jdbc.update("INSERT INTO ob_client_applications (ob_client_id, product_id) VALUES (?, ?)",
                clientId, product);
        return clientId;
    }

    private long insertProject(long clientId) {
        jdbc.update("""
                INSERT INTO ob_projects (ob_client_id, product_id, name, start_date,
                                         sales_person_id, implementor_user_id, created_by)
                VALUES (?, ?, ?, '2026-09-01', ?, ?, ?)
                """, clientId, product, "it_obcurstage_project_" + System.nanoTime(), owner, owner, owner);
        return lastId();
    }

    private long insertJourney(long projectId, long clientId, long templateId) {
        jdbc.update("""
                INSERT INTO ob_journeys (project_id, ob_client_id, product_id, template_id,
                                         service_name, gate_status, gate_opened_at, started_at)
                VALUES (?, ?, ?, ?, ?, 'OPEN', '2026-09-01 09:00:00', '2026-09-01 09:00:00')
                """, projectId, clientId, product, templateId, "it_obcurstage_service_" + System.nanoTime());
        return lastId();
    }

    private long insertStep(long journeyId, long templateStepId, int sequence, String name) {
        return insertStepWithStatus(journeyId, templateStepId, sequence, name, "PENDING");
    }

    private long insertStepWithStatus(long journeyId, long templateStepId, int sequence, String name,
                                      String status) {
        jdbc.update("""
                INSERT INTO ob_journey_steps (journey_id, template_step_id, sequence, name, status)
                VALUES (?, ?, ?, ?, ?)
                """, journeyId, templateStepId, sequence, name, status);
        return lastId();
    }

    private void insertItem(long stepId, int sequence, String label, Boolean answer) {
        jdbc.update("""
                INSERT INTO ob_journey_step_items (step_id, sequence, label, answer, remark)
                VALUES (?, ?, ?, ?, ?)
                """, stepId, sequence, label, answer, answer == null || answer ? null : "Not applicable");
        lastId();
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
