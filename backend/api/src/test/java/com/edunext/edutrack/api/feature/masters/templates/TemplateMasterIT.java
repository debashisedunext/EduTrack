package com.edunext.edutrack.api.feature.masters.templates;

import com.edunext.edutrack.domain.workflow.WorkflowTemplateMappingRepository;
import com.edunext.edutrack.domain.workflow.WorkflowTemplateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-041 · S-13 tab 3 against a real MySQL.
 *
 * <p>{@link TemplateServiceTest} and {@link TemplateResolverTest} prove the
 * decisions against mocks. This proves the four things a mock cannot:
 *
 * <ol>
 *   <li>🔴 <b>The generated columns make the unique key mean what it reads
 *     as.</b> This is the assertion that justifies the whole class. A plain
 *     {@code UNIQUE (project_id, task_type_id)} looks like it enforces "one
 *     template per pair" and does not — MySQL treats every NULL inside a unique
 *     index as distinct, so {@code (5, NULL)} inserts twice under two different
 *     templates and neither insert fails. The resolver then has two answers for
 *     rung 2 and returns whichever the optimiser reached first. Against a mocked
 *     repository the broken schema passes every test in this package.</li>
 *   <li><b>The ladder resolves against real rows</b>, including the two forms of
 *     wildcard that {@code findCandidates}' JPQL has to express with explicit
 *     null tests. A derived Spring Data query would generate
 *     {@code project_id = ?} for a null argument and silently match nothing.</li>
 *   <li><b>The migration's seed is shaped the way this screen assumes</b> —
 *     §4A.9's seven task-type rules across three templates, all wildcard on
 *     project. B-004's own seed was written by hand before any code validated
 *     it, and a seed that disagreed would show an Admin a configuration they did
 *     not create.</li>
 *   <li><b>The cascades are real.</b> Deleting a template takes its stages
 *     <em>and</em> its rules with it, both by {@code ON DELETE CASCADE}. A rule
 *     left pointing at a template that no longer exists resolves to nothing.</li>
 * </ol>
 *
 * <p>The fixture restores what it changes rather than working on rows of its own,
 * for {@code StageMasterIT}'s reason: the rules under test are the seeded ones
 * every other assertion here is about.
 */
@SpringBootTest
@Testcontainers
class TemplateMasterIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_template_master_it")
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
    TemplateResolver resolver;

    @Autowired
    WorkflowTemplateRepository templates;

    @Autowired
    WorkflowTemplateMappingRepository mappings;

    @Autowired
    JdbcTemplate jdbc;

    private long templateId(String name) {
        return templates.findByName(name).orElseThrow().getId();
    }

    private int taskTypeId(String code) {
        return jdbc.queryForObject("SELECT id FROM task_types WHERE code = ?", Integer.class, code);
    }

    /**
     * A project of this class's own, created on demand.
     *
     * <p><b>The migrations seed no projects at all</b>, which is worth stating
     * because it is not obvious and it is not an oversight: B-002 seeds task
     * types, B-003 statuses and B-004 templates, but a project is organisation
     * data rather than reference data, and B-007's 200-ticket corpus creates its
     * three at runtime. A `SELECT MIN(id) FROM projects` here returns null
     * against a database Flyway has just built.
     *
     * <p>So the project-scoped rungs get a fixture rather than borrowing a row,
     * and {@link #restoreSeed()} takes it away again — which also keeps
     * {@link #seedMatchesTheBlueprint()} true, since a leftover project would
     * outlive nothing but could still be routed to by a leftover rule.
     */
    private long fixtureProjectId() {
        Long existing = jdbc.queryForObject(
                "SELECT MAX(id) FROM projects WHERE project_code = 'B041IT'", Long.class);
        if (existing != null) {
            return existing;
        }
        jdbc.update("INSERT INTO projects (project_code, name, status) "
                + "VALUES ('B041IT', 'B-041 routing fixture', 'ACTIVE')");
        return jdbc.queryForObject(
                "SELECT id FROM projects WHERE project_code = 'B041IT'", Long.class);
    }

    /** Restore the migration's seven rules after anything that added to them. */
    @AfterEach
    void restoreSeed() {
        // Rules first. `fk_workflow_template_mappings_project` is RESTRICT — the
        // decision `projectDeleteIsRestricted` asserts — so the project cannot go
        // while one still names it, and this order is the same one an
        // organisation would have to follow.
        jdbc.update("DELETE FROM workflow_template_mappings WHERE project_id IS NOT NULL");
        jdbc.update("DELETE FROM workflow_template_mappings "
                + "WHERE project_id IS NULL AND task_type_id IS NULL");
        jdbc.update("DELETE FROM workflow_templates WHERE name NOT IN "
                + "('Standard Dev Flow','Support Fast-Track','Infra Flow')");
        jdbc.update("DELETE FROM projects WHERE project_code = 'B041IT'");
    }

    // ------------------------------------------------------------------
    // The one assertion a mock cannot make
    // ------------------------------------------------------------------

    /**
     * 🔴 The reason this class exists.
     *
     * <p>Written directly through {@link JdbcTemplate} rather than through the
     * service, deliberately: {@code TemplateService} checks for the collision
     * itself before writing, so going through it would prove the service's guard
     * and say nothing about the schema. What is being asserted here is that the
     * database refuses it <em>independently</em> — which is what stops a rule
     * arriving by migration, by a hand-written {@code UPDATE}, or by a future
     * service path that forgets the check.
     *
     * <p>With a plain {@code UNIQUE (project_id, task_type_id)} both inserts
     * succeed and this test fails. That is the whole point.
     */
    @Test
    @DisplayName("refuses a second rule on the same pair even when one side is NULL")
    void duplicateWildcardPairIsRefused() {
        long template = templateId("Infra Flow");
        int bug = taskTypeId("INTERNAL_BUG");

        jdbc.update("INSERT INTO workflow_template_mappings (template_id, project_id, task_type_id) "
                + "VALUES (?, NULL, ?)", template, bug);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO workflow_template_mappings (template_id, project_id, task_type_id) "
                        + "VALUES (?, NULL, ?)", templateId("Support Fast-Track"), bug))
                .isInstanceOf(DataAccessException.class);

        jdbc.update("DELETE FROM workflow_template_mappings WHERE task_type_id = ?", bug);
    }

    /** The same, for the pair that is wildcard on both sides. */
    @Test
    @DisplayName("refuses a second catch-all rule")
    void duplicateCatchAllIsRefused() {
        jdbc.update("INSERT INTO workflow_template_mappings (template_id, project_id, task_type_id) "
                + "VALUES (?, NULL, NULL)", templateId("Infra Flow"));

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO workflow_template_mappings (template_id, project_id, task_type_id) "
                        + "VALUES (?, NULL, NULL)", templateId("Support Fast-Track")))
                .isInstanceOf(DataAccessException.class);

        jdbc.update("DELETE FROM workflow_template_mappings "
                + "WHERE project_id IS NULL AND task_type_id IS NULL");
    }

    /**
     * The generated columns are {@code STORED} and never written by Java, so a
     * row inserted with nulls must carry zeroes in them. If Hibernate ever starts
     * mapping those columns this fails, which is the warning worth having.
     */
    @Test
    @DisplayName("collapses NULL to 0 in the two key columns")
    void generatedColumnsCollapseNull() {
        long template = templateId("Infra Flow");
        jdbc.update("INSERT INTO workflow_template_mappings (template_id, project_id, task_type_id) "
                + "VALUES (?, NULL, NULL)", template);

        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "SELECT project_key, task_type_key FROM workflow_template_mappings "
                        + "WHERE project_id IS NULL AND task_type_id IS NULL");

        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0).get("project_key")).longValue()).isZero();
        assertThat(((Number) rows.get(0).get("task_type_key")).longValue()).isZero();

        jdbc.update("DELETE FROM workflow_template_mappings "
                + "WHERE project_id IS NULL AND task_type_id IS NULL");
    }

    // ------------------------------------------------------------------
    // The ladder, against real rows
    // ------------------------------------------------------------------

    /**
     * §4A.9's seed, asserted rather than trusted.
     *
     * <p>B-004's own stage seed was written by hand before any code validated it,
     * and B-040 records what that cost. The same applies here: a seed disagreeing
     * with what the screen assumes would show an Admin a routing configuration
     * they did not create and cannot explain.
     */
    @Test
    @DisplayName("seeds §4A.9's seven task-type rules, all wildcard on project")
    void seedMatchesTheBlueprint() {
        assertThat(mappings.findAll()).hasSize(7);
        assertThat(mappings.findAll()).allSatisfy(m -> {
            assertThat(m.getProjectId()).isNull();
            assertThat(m.getTaskTypeId()).isNotNull();
        });

        assertThat(mappings.findByTemplateIdOrderByIdAsc(templateId("Standard Dev Flow"))).hasSize(3);
        assertThat(mappings.findByTemplateIdOrderByIdAsc(templateId("Support Fast-Track"))).hasSize(2);
        assertThat(mappings.findByTemplateIdOrderByIdAsc(templateId("Infra Flow"))).hasSize(2);
    }

    /**
     * The wildcard predicate, against SQL rather than against a mock.
     *
     * <p>Spring Data cannot express "column IS NULL when the parameter is null"
     * in a derived name — {@code findByProjectIdAndTaskTypeId(null, 3)} generates
     * {@code project_id = ?} and matches nothing, silently. This is what proves
     * the explicit JPQL null tests do the other thing.
     */
    @Test
    @DisplayName("resolves a task-type rule through the wildcard on project")
    void taskTypeRuleResolves() {
        TemplateDtos.TemplateResolution answer =
                resolver.explain(fixtureProjectId(), taskTypeId("SERVER_ISSUE"));

        assertThat(answer.templateId()).isEqualTo(templateId("Infra Flow"));
        assertThat(answer.rung()).isEqualTo("TASK_TYPE");
    }

    @Test
    @DisplayName("prefers an exact pair over the seeded task-type rule")
    void exactRuleBeatsTheSeed() {
        long project = fixtureProjectId();
        int serverIssue = taskTypeId("SERVER_ISSUE");
        jdbc.update("INSERT INTO workflow_template_mappings (template_id, project_id, task_type_id) "
                + "VALUES (?, ?, ?)", templateId("Support Fast-Track"), project, serverIssue);

        TemplateDtos.TemplateResolution answer = resolver.explain(project, serverIssue);

        assertThat(answer.templateId()).isEqualTo(templateId("Support Fast-Track"));
        assertThat(answer.rung()).isEqualTo("EXACT");
    }

    /**
     * The tie-break, against real rows. Two rules, one rank apiece, and the
     * project one has to win — see {@code TemplateResolver}'s header for why that
     * precedence and not the other.
     */
    @Test
    @DisplayName("prefers a project rule over a task-type rule at equal specificity")
    void projectBeatsTaskTypeInSql() {
        long project = fixtureProjectId();
        int serverIssue = taskTypeId("SERVER_ISSUE");
        jdbc.update("INSERT INTO workflow_template_mappings (template_id, project_id, task_type_id) "
                + "VALUES (?, ?, NULL)", templateId("Support Fast-Track"), project);

        TemplateDtos.TemplateResolution answer = resolver.explain(project, serverIssue);

        assertThat(answer.templateId()).isEqualTo(templateId("Support Fast-Track"));
        assertThat(answer.rung()).isEqualTo("PROJECT");
    }

    /**
     * The fallback, and the case §4A.9 has no other way to surface: a pair nobody
     * wrote a rule for still routes somewhere.
     */
    @Test
    @DisplayName("falls through to the seeded default for an unmapped task type")
    void unmappedPairFallsThroughToDefault() {
        TemplateDtos.TemplateResolution answer =
                resolver.explain(fixtureProjectId(), taskTypeId("INTERNAL_BUG"));

        assertThat(answer.templateId()).isEqualTo(templateId("Standard Dev Flow"));
        assertThat(answer.rung()).isEqualTo("DEFAULT");
        assertThat(answer.mappingId()).isNull();
    }

    /**
     * B-004 seeds exactly one default, and the last rung of the ladder is
     * meaningless if that stops being true. The service holds it; nothing in the
     * schema does.
     */
    @Test
    @DisplayName("finds exactly one default template in the seed")
    void exactlyOneDefault() {
        assertThat(templates.findByIsDefaultTrueAndIsActiveTrue()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // The cascades
    // ------------------------------------------------------------------

    /**
     * Both {@code ON DELETE CASCADE}s, asserted together because deleting a
     * template is the one operation that depends on both.
     */
    @Test
    @DisplayName("takes the stages and the rules with a deleted template")
    void deleteCascadesBothWays() {
        jdbc.update("INSERT INTO workflow_templates (name, description, is_default, is_active) "
                + "VALUES ('Cascade Fixture', NULL, 0, 1)");
        long id = jdbc.queryForObject(
                "SELECT id FROM workflow_templates WHERE name = 'Cascade Fixture'", Long.class);
        jdbc.update("INSERT INTO workflow_stages (template_id, seq, stage_code, display_name, "
                + "owner_role) VALUES (?, 10, 'DEV', 'Development', 'DEVELOPER')", id);
        jdbc.update("INSERT INTO workflow_template_mappings (template_id, project_id, task_type_id) "
                + "VALUES (?, ?, NULL)", id, fixtureProjectId());

        jdbc.update("DELETE FROM workflow_templates WHERE id = ?", id);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_stages WHERE template_id = ?", Long.class, id))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_template_mappings WHERE template_id = ?", Long.class, id))
                .isZero();
    }

    /**
     * The opposite call, and the one worth stating: a mapping is <b>not</b> a
     * fact about the project. Cascading it away with the project would silently
     * re-route every task type that rule covered to the default template, so the
     * foreign key is {@code RESTRICT} and the delete fails loudly instead.
     *
     * <p>Neither master offers a hard delete today, so this asserts insurance
     * rather than a live path — which is exactly why it would otherwise go
     * unnoticed if somebody "tidied" the constraint.
     */
    @Test
    @DisplayName("refuses to delete a project a routing rule still names")
    void projectDeleteIsRestricted() {
        long project = fixtureProjectId();
        jdbc.update("INSERT INTO workflow_template_mappings (template_id, project_id, task_type_id) "
                + "VALUES (?, ?, NULL)", templateId("Infra Flow"), project);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM projects WHERE id = ?", project))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("accepts a rule naming a project that is closed rather than active")
    void closedProjectMayStillBeRouted() {
        long project = fixtureProjectId();
        jdbc.update("UPDATE projects SET status = 'CLOSED' WHERE id = ?", project);

        assertThatCode(() -> jdbc.update(
                "INSERT INTO workflow_template_mappings (template_id, project_id, task_type_id) "
                        + "VALUES (?, ?, NULL)", templateId("Infra Flow"), project))
                .doesNotThrowAnyException();

        jdbc.update("UPDATE projects SET status = 'ACTIVE' WHERE id = ?", project);
    }
}
