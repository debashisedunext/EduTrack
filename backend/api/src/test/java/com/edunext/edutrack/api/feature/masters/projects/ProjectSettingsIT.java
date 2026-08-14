package com.edunext.edutrack.api.feature.masters.projects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-019 · the Settings tab against a real MySQL.
 *
 * <p>{@code ProjectSettingsServiceTest} proves the decisions against mocks.
 * This proves the half a mock cannot:
 *
 * <ul>
 *   <li><b>that a project that predates this feature reads as unrestricted</b> —
 *       the assertion the migration lives or dies by, because if an absent
 *       allow-list meant "nothing may be raised", applying it would have stopped
 *       ticket creation on every project at once;</li>
 *   <li>that {@code mandatory_fields} round-trips through the {@code JSON}
 *       column, and that {@code ck_projects_mandatory_fields} accepts what the
 *       service writes and refuses what it does not;</li>
 *   <li>that {@code project_task_types}' composite primary key and its two
 *       foreign keys behave as the service assumes;</li>
 *   <li><b>that a retired task type's membership survives a read</b>, which is
 *       a {@code LEFT JOIN} predicate and is exactly the sort of thing that is
 *       right in a mock and wrong in SQL.</li>
 * </ul>
 *
 * <p>Fixture rows are prefixed {@code ITPS} so nothing collides with the seed or
 * with the project, team, SLA, resource and role suites, and the cleanup can be
 * exact.
 *
 * <h2>A CHECK violation is not a {@code DataIntegrityViolationException}</h2>
 *
 * <p>Worth knowing before writing the next one of these. MySQL reports a
 * violated {@code CHECK} as error <b>3819</b> with SQLSTATE {@code HY000}, which
 * Spring's translator does not categorise — it arrives as an
 * {@code UncategorizedSQLException}. A unique or foreign key violation
 * <i>does</i> map to {@link DataIntegrityViolationException}, so the two look
 * alike in the migration and behave differently in a test. These assert
 * {@link DataAccessException} and the constraint's <b>name</b>, which is the
 * sharper assertion anyway: it proves <i>which</i> rule fired rather than that
 * something went wrong.
 */
@SpringBootTest
@Testcontainers
class ProjectSettingsIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_project_settings_it")
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
    ProjectSettingsService service;

    @Autowired
    JdbcTemplate jdbc;

    private long projectId;
    private long otherProjectId;
    private int prodBugId;
    private int changeRequestId;
    private int browserIssueId;

    @BeforeEach
    void seed() {
        clearFixtureRows();

        jdbc.update("INSERT INTO projects (project_code, name) VALUES ('ITPS', 'Settings tab fixture')");
        jdbc.update("INSERT INTO projects (project_code, name) VALUES ('ITPX', 'Settings tab neighbour')");
        projectId = projectId("ITPS");
        otherProjectId = projectId("ITPX");

        prodBugId = taskTypeId("PRODUCTION_BUG");
        changeRequestId = taskTypeId("CHANGE_REQUEST");
        browserIssueId = taskTypeId("BROWSER_ISSUE");
    }

    @AfterEach
    void cleanUp() {
        clearFixtureRows();
        jdbc.update("UPDATE task_types SET is_active = 1 WHERE id = ?", browserIssueId);
    }

    // ------------------------------------------------------------------
    // the state every existing project is in
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a project with no rows is unrestricted, and every active task type reads as allowed")
    void anUnconfiguredProjectIsUnrestricted() {
        // The assertion the migration lives or dies by. Every project in the
        // system is in this state, because the table did not exist until it
        // ran; had the absence meant "nothing may be raised", applying it would
        // have stopped ticket creation everywhere at once.
        ProjectSettingsDtos.ProjectSettings settings = service.settings(projectId);

        assertThat(settings.restrictsTaskTypes()).isFalse();
        assertThat(settings.taskTypes()).hasSize(11);
        assertThat(settings.taskTypes())
                .extracting(ProjectSettingsDtos.SettingsTaskType::isAllowed)
                .containsOnly(true);
    }

    @Test
    @DisplayName("and its columns read as the migration's defaults, not as null")
    void anUnconfiguredProjectHasDefaults() {
        ProjectSettingsDtos.ProjectSettings settings = service.settings(projectId);

        assertThat(settings.autoAssignRule()).isEqualTo(ProjectSettingsDtos.AutoAssignRule.MANUAL);
        // The column is NULL for every row that predates this feature, and the
        // repository collapses NULL and [] so nothing downstream can tell.
        assertThat(settings.mandatoryFields()).isEmpty();
        assertThat(storedMandatoryFields(projectId)).isNull();
    }

    @Test
    @DisplayName("the grid is ordered by the master's seq — Change Request first")
    void isOrderedByMasterSequence() {
        assertThat(service.settings(projectId).taskTypes().get(0).code()).isEqualTo("CHANGE_REQUEST");
    }

    // ------------------------------------------------------------------
    // the allow-list
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a saved allow-list restricts this project and leaves its neighbour alone")
    void anAllowListIsScopedToItsProject() {
        service.replace(projectId, write("MANUAL", List.of(), List.of(prodBugId)));

        assertThat(service.settings(projectId).restrictsTaskTypes()).isTrue();
        assertThat(allowedCodes(projectId)).containsExactly("PRODUCTION_BUG");
        assertThat(service.settings(otherProjectId).restrictsTaskTypes()).isFalse();
    }

    @Test
    @DisplayName("a second replace clears what it omits")
    void aReplaceClearsWhatItOmits() {
        service.replace(projectId, write("MANUAL", List.of(), List.of(prodBugId)));
        service.replace(projectId, write("MANUAL", List.of(), List.of(changeRequestId)));

        assertThat(allowedCodes(projectId)).containsExactly("CHANGE_REQUEST");
    }

    @Test
    @DisplayName("an empty allow-list removes the restriction and deletes the rows")
    void anEmptyAllowListRemovesTheRestriction() {
        service.replace(projectId, write("MANUAL", List.of(), List.of(prodBugId)));
        service.replace(projectId, write("MANUAL", List.of(), List.of()));

        assertThat(service.settings(projectId).restrictsTaskTypes()).isFalse();
        assertThat(allowedCodes(projectId)).isEmpty();
        assertThat(service.settings(projectId).taskTypes())
                .extracting(ProjectSettingsDtos.SettingsTaskType::isAllowed)
                .containsOnly(true);
    }

    @Test
    @DisplayName("a retired task type this project allows is still returned, flagged inactive")
    void aRetiredTaskTypeSurvivesTheRead() {
        // The LEFT JOIN predicate, and the thing a mock cannot settle. If the
        // read were `WHERE tt.is_active = 1`, this row would vanish from the
        // screen and the next save — assembled from the rows the screen was
        // given — would delete a membership nobody was shown.
        service.replace(projectId, write("MANUAL", List.of(), List.of(prodBugId, browserIssueId)));
        jdbc.update("UPDATE task_types SET is_active = 0 WHERE id = ?", browserIssueId);

        ProjectSettingsDtos.ProjectSettings settings = service.settings(projectId);

        assertThat(settings.taskTypes())
                .filteredOn(t -> t.taskTypeId() == browserIssueId)
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.isActive()).isFalse();
                    assertThat(t.isAllowed()).isTrue();
                });
    }

    @Test
    @DisplayName("an unrestricted project never shows a retired task type — 'all active' is what it means")
    void anUnrestrictedProjectHidesRetiredTypes() {
        jdbc.update("UPDATE task_types SET is_active = 0 WHERE id = ?", browserIssueId);

        assertThat(service.settings(projectId).taskTypes())
                .extracting(ProjectSettingsDtos.SettingsTaskType::taskTypeId)
                .doesNotContain(browserIssueId);
    }

    @Test
    @DisplayName("the composite primary key is what makes the duplicate check load-bearing")
    void theKeyRefusesADuplicateRow() {
        // The service refuses a repeated id first, with a 400 naming the task
        // type. This asserts what would happen if it did not — the failure the
        // check is standing in front of.
        jdbc.update("INSERT INTO project_task_types (project_id, task_type_id) VALUES (?, ?)",
                projectId, prodBugId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO project_task_types (project_id, task_type_id) VALUES (?, ?)",
                projectId, prodBugId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------
    // mandatory fields and the JSON column
    // ------------------------------------------------------------------

    @Test
    @DisplayName("mandatory fields round-trip through the JSON column, in order")
    void mandatoryFieldsRoundTrip() {
        service.replace(projectId, write("MANUAL",
                List.of("MODULE", "ESTIMATED_HRS", "DESCRIPTION"), List.of()));

        assertThat(service.settings(projectId).mandatoryFields()).containsExactly(
                ProjectSettingsDtos.TicketField.MODULE,
                ProjectSettingsDtos.TicketField.ESTIMATED_HRS,
                ProjectSettingsDtos.TicketField.DESCRIPTION);
    }

    @Test
    @DisplayName("an emptied list is stored as NULL, not as []")
    void anEmptyListIsStoredAsNull() {
        // Two representations of one state is a distinction every reader would
        // otherwise have to remember; the repository writes only one of them
        // and collapses both on the way back.
        service.replace(projectId, write("MANUAL", List.of("MODULE"), List.of()));
        assertThat(storedMandatoryFields(projectId)).isNotNull();

        service.replace(projectId, write("MANUAL", List.of(), List.of()));
        assertThat(storedMandatoryFields(projectId)).isNull();
        assertThat(service.settings(projectId).mandatoryFields()).isEmpty();
    }

    @Test
    @DisplayName("ck_projects_mandatory_fields refuses a duplicate the service would have caught")
    void theCheckRefusesADuplicate() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE projects SET mandatory_fields = CAST(? AS JSON) WHERE id = ?",
                "[\"MODULE\", \"MODULE\"]", projectId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_projects_mandatory_fields");
    }

    @Test
    @DisplayName("ck_projects_mandatory_fields refuses a non-array and a lower-cased code")
    void theCheckRefusesMalformedValues() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE projects SET mandatory_fields = CAST(? AS JSON) WHERE id = ?",
                "{\"module\": true}", projectId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_projects_mandatory_fields");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE projects SET mandatory_fields = CAST(? AS JSON) WHERE id = ?",
                "[\"module\"]", projectId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_projects_mandatory_fields");
    }

    @Test
    @DisplayName("the CHECK constrains shape and not vocabulary, so a future code is storable")
    void theCheckDoesNotPinTheVocabulary() {
        // Deliberate — the list tracks Stream C's create form, and pinning the
        // values would mean C cannot add a form field without a migration in
        // Stream B's directory. The service is what refuses one on the way in,
        // and drops one it does not recognise on the way out.
        jdbc.update("UPDATE projects SET mandatory_fields = CAST(? AS JSON) WHERE id = ?",
                "[\"MODULE\", \"SOME_FUTURE_FIELD\"]", projectId);

        assertThat(service.settings(projectId).mandatoryFields())
                .containsExactly(ProjectSettingsDtos.TicketField.MODULE);
    }

    // ------------------------------------------------------------------
    // the auto-assign rule
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the rule is written to the column the General tab reads")
    void theRuleIsWrittenToTheSharedColumn() {
        // One column, one writer as of B-019 — but the General tab still reads
        // it, and PATCH /projects/{projectId} can still set it. This asserts
        // they are the same column rather than two that happen to agree.
        service.replace(projectId, write("LEAST_LOADED", List.of(), List.of()));

        assertThat(jdbc.queryForObject(
                "SELECT auto_assign_rule FROM projects WHERE id = ?", String.class, projectId))
                .isEqualTo("LEAST_LOADED");
    }

    @Test
    @DisplayName("ck_projects_auto_assign_rule and the enum agree — every name stores, a fourth does not")
    void theCheckAndTheEnumAgree() {
        // The half that matters: every value the enum offers is one the column
        // will take. A constraint and a vocabulary that disagree fail at the
        // database, on somebody's save, with a message naming a MySQL index.
        for (String rule : ProjectSettingsService.RULE_NAMES) {
            jdbc.update("UPDATE projects SET auto_assign_rule = ? WHERE id = ?", rule, projectId);
        }

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE projects SET auto_assign_rule = ? WHERE id = ?", "WHOEVER_IS_FREE", projectId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_projects_auto_assign_rule");
    }

    // ------------------------------------------------------------------
    // refusals
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unknown project is 404 on both operations, never 403")
    void unknownProjectIsNotFound() {
        assertThatThrownBy(() -> service.settings(999_999L))
                .isInstanceOf(ProjectSettingsService.NoSuchProjectException.class);
        assertThatThrownBy(() -> service.replace(999_999L, write("MANUAL", List.of(), List.of())))
                .isInstanceOf(ProjectSettingsService.NoSuchProjectException.class);
    }

    @Test
    @DisplayName("an unknown task type is refused before anything is written")
    void unknownTaskTypeLeavesTheAllowListAlone() {
        service.replace(projectId, write("MANUAL", List.of(), List.of(prodBugId)));

        assertThatThrownBy(() -> service.replace(projectId,
                write("MANUAL", List.of(), List.of(prodBugId, 999_999))))
                .isInstanceOf(ProjectSettingsService.SettingsValidationException.class);

        // The body is one transaction. A row refused halfway through would roll
        // back the rows before it and leave the caller told about the second id
        // of a save that also silently did not apply the first.
        assertThat(allowedCodes(projectId)).containsExactly("PRODUCTION_BUG");
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static ProjectSettingsDtos.ProjectSettingsWrite write(
            String rule, List<String> fields, List<Integer> taskTypeIds) {
        return new ProjectSettingsDtos.ProjectSettingsWrite(rule, fields, taskTypeIds);
    }

    private List<String> allowedCodes(long id) {
        return jdbc.queryForList("""
                SELECT tt.code
                  FROM project_task_types ptt
                  JOIN task_types tt ON tt.id = ptt.task_type_id
                 WHERE ptt.project_id = ?
                 ORDER BY tt.seq, tt.id
                """, String.class, id);
    }

    private String storedMandatoryFields(long id) {
        return jdbc.queryForObject(
                "SELECT mandatory_fields FROM projects WHERE id = ?", String.class, id);
    }

    private long projectId(String code) {
        return jdbc.queryForObject(
                "SELECT id FROM projects WHERE project_code = ?", Long.class, code);
    }

    private int taskTypeId(String code) {
        return jdbc.queryForObject(
                "SELECT id FROM task_types WHERE code = ?", Integer.class, code);
    }

    /**
     * {@code project_task_types} rows go with the project by cascade, but the
     * sweep names them anyway: a test that depends on a cascade to clean up
     * silently stops cleaning up if the cascade is ever narrowed.
     */
    private void clearFixtureRows() {
        jdbc.update("""
                DELETE FROM project_task_types
                 WHERE project_id IN (SELECT id FROM projects WHERE project_code IN ('ITPS', 'ITPX'))
                """);
        jdbc.update("DELETE FROM projects WHERE project_code IN ('ITPS', 'ITPX')");
    }
}
