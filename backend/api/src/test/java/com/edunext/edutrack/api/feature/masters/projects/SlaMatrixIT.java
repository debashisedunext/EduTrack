package com.edunext.edutrack.api.feature.masters.projects;

import com.edunext.edutrack.api.feature.tickets.PlannedCloseDateService;
import com.edunext.edutrack.api.feature.tickets.SlaResolution;
import org.junit.jupiter.api.AfterEach;
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

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-018 · the SLA tab against a real MySQL.
 *
 * <p>{@code SlaMatrixServiceTest} proves the decisions against mocks. This
 * proves the half a mock cannot:
 *
 * <ul>
 *   <li>that {@code uq_sla_policies} really makes the write an upsert, so a
 *       cleared cell can be restored rather than colliding forever;</li>
 *   <li><b>that a replace does not delete rows</b>, because
 *       {@code clients.sla_policy_id} is a foreign key into this table and a
 *       {@code DELETE} fails on a constraint naming a MySQL index rather than
 *       anything a caller can act on;</li>
 *   <li><b>that a project-level default survives a replace</b> — the row with
 *       {@code task_type_id IS NULL} that this grid has no cell for, and that a
 *       carelessly scoped {@code UPDATE} would silently switch off;</li>
 *   <li><b>that this grid and C-012's planned close date answer the same
 *       thing</b>, which is the whole reason the ladder may be written down
 *       twice. Neither implementation can be read to establish it.</li>
 * </ul>
 *
 * <p>Fixture rows are prefixed {@code ITSL} so nothing collides with the seed or
 * with the project, team, resource and role suites, and the cleanup can be
 * exact. The task types and priorities are the migration's real seed — eleven
 * and four — because the grid's size and ordering are part of what is being
 * asserted.
 */
@SpringBootTest
@Testcontainers
class SlaMatrixIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_sla_matrix_it")
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
    SlaMatrixService service;

    /** C-012's, on the other side of the agreement test. */
    @Autowired
    PlannedCloseDateService plannedCloseDates;

    @Autowired
    JdbcTemplate jdbc;

    private long projectId;
    private long otherProjectId;
    private int prodBugId;
    private int changeRequestId;

    @BeforeEach
    void seed() {
        clearFixtureRows();

        jdbc.update("INSERT INTO projects (project_code, name) VALUES ('ITSL', 'SLA tab fixture')");
        jdbc.update("INSERT INTO projects (project_code, name) VALUES ('ITSX', 'SLA tab neighbour')");
        projectId = projectId("ITSL");
        otherProjectId = projectId("ITSX");

        prodBugId = taskTypeId("PRODUCTION_BUG");
        changeRequestId = taskTypeId("CHANGE_REQUEST");
    }

    @AfterEach
    void cleanUp() {
        clearFixtureRows();
    }

    // ------------------------------------------------------------------
    // the grid
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unconfigured project still gets a full grid, from the masters' defaults")
    void anUnconfiguredProjectStillHasAGrid() {
        // Every project on its first day. If the ladder stopped at the three
        // policy rungs the way SlaPolicyRepository's javadoc does, this screen
        // would show forty-four blank cells for a product that computes a
        // perfectly good planned close date for all of them.
        List<SlaPolicyDtos.SlaCell> grid = service.matrix(projectId);

        assertThat(grid).hasSize(11 * 4);
        assertThat(grid).noneMatch(SlaPolicyDtos.SlaCell::isOverride);
        assertThat(grid).allSatisfy(c -> assertThat(c.resolutionHrs()).isNotNull());
        // Every level in the seed has a default_sla_hours, so rung 4 answers
        // before rung 5 ever gets a turn.
        assertThat(grid).allSatisfy(c ->
                assertThat(c.source()).isEqualTo(SlaPolicyDtos.Source.PRIORITY_DEFAULT));
    }

    @Test
    @DisplayName("the grid is ordered by the masters' seq — Change Request first, Low to Critical")
    void isOrderedByMasterSequence() {
        List<SlaPolicyDtos.SlaCell> grid = service.matrix(projectId);

        assertThat(grid.subList(0, 4)).extracting(SlaPolicyDtos.SlaCell::taskTypeCode)
                .containsOnly("CHANGE_REQUEST");
        assertThat(grid.subList(0, 4)).extracting(SlaPolicyDtos.SlaCell::level)
                .containsExactly("LOW", "MEDIUM", "HIGH", "CRITICAL");
    }

    // ------------------------------------------------------------------
    // the replace
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a saved override reads back as an override, and only for its own cell")
    void anOverrideIsWrittenAndRead() {
        service.replace(projectId, List.of(override(prodBugId, "HIGH", 2, 6)));

        List<SlaPolicyDtos.SlaCell> grid = service.matrix(projectId);

        assertThat(cell(grid, prodBugId, "HIGH")).satisfies(c -> {
            assertThat(c.source()).isEqualTo(SlaPolicyDtos.Source.PROJECT_TASK_TYPE);
            assertThat(c.isOverride()).isTrue();
            assertThat(c.responseHrs()).isEqualByComparingTo(BigDecimal.valueOf(2));
            assertThat(c.resolutionHrs()).isEqualByComparingTo(BigDecimal.valueOf(6));
        });
        assertThat(cell(grid, prodBugId, "LOW").isOverride()).isFalse();
        assertThat(cell(grid, changeRequestId, "HIGH").isOverride()).isFalse();
    }

    @Test
    @DisplayName("a second replace clears the overrides the first one wrote")
    void aReplaceClearsWhatItOmits() {
        service.replace(projectId, List.of(override(prodBugId, "HIGH", 2, 6)));
        service.replace(projectId, List.of(override(changeRequestId, "LOW", 8, 48)));

        List<SlaPolicyDtos.SlaCell> grid = service.matrix(projectId);

        assertThat(cell(grid, prodBugId, "HIGH").isOverride()).isFalse();
        assertThat(cell(grid, changeRequestId, "LOW").isOverride()).isTrue();
    }

    @Test
    @DisplayName("clearing an override and restoring it reuses the row — uq_sla_policies makes it an upsert")
    void restoringAClearedCellDoesNotCollide() {
        // Rows are deactivated rather than deleted, so a plain INSERT would
        // collide the first time anybody restored a cell they had cleared — and
        // the collision would read as a bug in the save button.
        service.replace(projectId, List.of(override(prodBugId, "HIGH", 2, 6)));
        long firstId = overrideId(prodBugId, "HIGH");

        service.replace(projectId, List.of());
        service.replace(projectId, List.of(override(prodBugId, "HIGH", 1, 4)));

        assertThat(overrideId(prodBugId, "HIGH")).isEqualTo(firstId);
        assertThat(cell(service.matrix(projectId), prodBugId, "HIGH").resolutionHrs())
                .isEqualByComparingTo(BigDecimal.valueOf(4));
    }

    @Test
    @DisplayName("a cleared override is deactivated, never deleted — clients.sla_policy_id points at it")
    void aClearedOverrideIsDeactivatedNotDeleted() {
        // clients.sla_policy_id is a foreign key into this table with no
        // cascade. A DELETE fails on a constraint naming a MySQL index, or —
        // if somebody "fixes" that with a cascade — silently unsets a client's
        // SLA policy from a project screen.
        service.replace(projectId, List.of(override(prodBugId, "HIGH", 2, 6)));
        long policyId = overrideId(prodBugId, "HIGH");

        service.replace(projectId, List.of());

        assertThat(rowExists(policyId)).isTrue();
        assertThat(isActive(policyId)).isFalse();
    }

    @Test
    @DisplayName("a deactivated override falls through to the next rung rather than leaving a hole")
    void aDeactivatedOverrideFallsThrough() {
        // is_active = 0 is what the resolution ladder already reads, which is
        // exactly why deactivating is a safe way to clear a cell.
        service.replace(projectId, List.of(override(prodBugId, "HIGH", 2, 6)));
        service.replace(projectId, List.of());

        assertThat(cell(service.matrix(projectId), prodBugId, "HIGH")).satisfies(c -> {
            assertThat(c.source()).isEqualTo(SlaPolicyDtos.Source.PRIORITY_DEFAULT);
            assertThat(c.resolutionHrs()).isEqualByComparingTo(BigDecimal.valueOf(8));
        });
    }

    @Test
    @DisplayName("a project-level default survives a replace — the grid has no cell for it")
    void aProjectLevelDefaultSurvivesAReplace() {
        // The row with task_type_id IS NULL: §6's rung between this project's
        // overrides and the org-wide default. A task type × level grid cannot
        // express one, so a replace that dropped it would delete configuration
        // through a screen that never displayed it. B-007's corpus puts one on
        // PAY, which is what makes this a live case.
        insertPolicy(projectId, null, "CRITICAL", 1, 3);

        service.replace(projectId, List.of(override(prodBugId, "HIGH", 2, 6)));
        service.replace(projectId, List.of());

        assertThat(cell(service.matrix(projectId), changeRequestId, "CRITICAL")).satisfies(c -> {
            assertThat(c.source()).isEqualTo(SlaPolicyDtos.Source.PROJECT_LEVEL);
            assertThat(c.resolutionHrs()).isEqualByComparingTo(BigDecimal.valueOf(3));
        });
    }

    @Test
    @DisplayName("a replace touches this project only — a neighbour's overrides are untouched")
    void aReplaceIsScopedToOneProject() {
        insertPolicy(otherProjectId, prodBugId, "HIGH", 1, 2);

        service.replace(projectId, List.of());

        assertThat(cell(service.matrix(otherProjectId), prodBugId, "HIGH")).satisfies(c -> {
            assertThat(c.source()).isEqualTo(SlaPolicyDtos.Source.PROJECT_TASK_TYPE);
            assertThat(c.resolutionHrs()).isEqualByComparingTo(BigDecimal.valueOf(2));
        });
    }

    @Test
    @DisplayName("the org-wide default is never touched by a project's replace")
    void theOrgWideDefaultIsUntouched() {
        insertPolicy(null, null, "HIGH", 4, 24);

        service.replace(projectId, List.of(override(prodBugId, "HIGH", 2, 6)));
        service.replace(projectId, List.of());

        assertThat(cell(service.matrix(projectId), prodBugId, "HIGH")).satisfies(c -> {
            assertThat(c.source()).isEqualTo(SlaPolicyDtos.Source.ORG_DEFAULT);
            assertThat(c.resolutionHrs()).isEqualByComparingTo(BigDecimal.valueOf(24));
        });
    }

    @Test
    @DisplayName("the escalation flags round-trip as flags — TINYINT(1), not a user id")
    void theEscalationFlagsRoundTrip() {
        // The contract carried l1EscalationUserId / l2EscalationUserId until
        // this task. There is no column for either; these are the columns.
        service.replace(projectId, List.of(new SlaPolicyDtos.SlaPolicyWrite(
                prodBugId, "CRITICAL", BigDecimal.ONE, BigDecimal.valueOf(4), false, true)));

        assertThat(cell(service.matrix(projectId), prodBugId, "CRITICAL")).satisfies(c -> {
            assertThat(c.escalateToL1()).isFalse();
            assertThat(c.escalateToL2()).isTrue();
        });
    }

    @Test
    @DisplayName("a null response target is stored as NULL, not as zero")
    void aNullResponseTargetStaysNull() {
        // A policy that only targets resolution is a real one, and the column
        // is nullable for it. Zero is a different claim and would render as a
        // response target of "immediately".
        service.replace(projectId, List.of(new SlaPolicyDtos.SlaPolicyWrite(
                prodBugId, "HIGH", null, BigDecimal.valueOf(6), true, false)));

        assertThat(cell(service.matrix(projectId), prodBugId, "HIGH").responseHrs()).isNull();
    }

    @Test
    @DisplayName("a refused body leaves the previous matrix intact — one transaction, all or nothing")
    void aRefusedBodyRollsBack() {
        service.replace(projectId, List.of(override(prodBugId, "HIGH", 2, 6)));

        assertThatThrownBy(() -> service.replace(projectId, List.of(
                override(changeRequestId, "LOW", 8, 48),
                override(prodBugId, "NOT_A_LEVEL", 1, 2))))
                .isInstanceOf(SlaMatrixService.SlaValidationException.class);

        List<SlaPolicyDtos.SlaCell> grid = service.matrix(projectId);
        assertThat(cell(grid, prodBugId, "HIGH").isOverride()).isTrue();
        assertThat(cell(grid, changeRequestId, "LOW").isOverride()).isFalse();
    }

    @Test
    @DisplayName("an unknown project is 404, never an empty grid")
    void anUnknownProjectIs404() {
        assertThatThrownBy(() -> service.matrix(999_999L))
                .isInstanceOf(SlaMatrixService.NoSuchProjectException.class);
    }

    // ------------------------------------------------------------------
    // the agreement
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every cell of the grid answers what C-012's planned close date answers")
    void theGridAgreesWithThePlannedCloseDate() {
        // The reason the §6 ladder may be written down twice. C-012's resolver
        // walks it one cell at a time through SlaPolicyRepository; this feature
        // walks it in memory over three bounded reads, because forty-four cells
        // × five rungs is two hundred and twenty round trips for one page load.
        //
        // Nothing about either implementation can be *read* to establish that
        // they agree. If they drift, the SLA tab and the create form quote
        // different numbers at the same client and each screen looks correct on
        // its own.
        //
        // Every rung is exercised: an override, a project-level default, an
        // org-wide default, and the two master defaults everywhere else.
        insertPolicy(projectId, prodBugId, "HIGH", 2, 6);
        insertPolicy(projectId, null, "CRITICAL", 1, 3);
        insertPolicy(null, null, "MEDIUM", 6, 30);

        for (SlaPolicyDtos.SlaCell cell : service.matrix(projectId)) {
            SlaResolution theirs =
                    plannedCloseDates.resolve(projectId, cell.taskTypeId(), cell.level());

            String where = cell.taskTypeCode() + "/" + cell.level();

            assertThat(cell.source().name())
                    .as("source for %s", where)
                    .isEqualTo(theirs.source().name());
            assertThat(sameHours(cell.resolutionHrs(), theirs.resolutionHrs()))
                    .as("resolutionHrs for %s: %s vs %s", where, cell.resolutionHrs(), theirs.resolutionHrs())
                    .isTrue();
            assertThat(sameHours(cell.responseHrs(), theirs.responseHrs()))
                    .as("responseHrs for %s: %s vs %s", where, cell.responseHrs(), theirs.responseHrs())
                    .isTrue();
        }
    }

    /**
     * Null-safe and scale-insensitive.
     *
     * <p>Both matter. Null is a real answer on both sides — a rung that carries
     * no response target, or nothing answering at all — and {@code equals} on
     * {@code BigDecimal} distinguishes {@code 6} from {@code 6.00}, which the
     * {@code DECIMAL(6,2)} column and the two read paths can legitimately
     * disagree about without disagreeing about anything.
     */
    private static boolean sameHours(BigDecimal ours, BigDecimal theirs) {
        if (ours == null || theirs == null) {
            return ours == theirs;
        }
        return ours.compareTo(theirs) == 0;
    }

    @Test
    @DisplayName("the two ladders name their rungs identically — a renamed source would silently pass")
    void theSourceVocabulariesAreTheSame() {
        // The agreement test above compares source *names*, so it is only as
        // strong as the two enums having the same members. SlaPolicyDtos.Source
        // is a copy of SlaResolution.Source — copied rather than imported, to
        // keep a masters DTO off a tickets type — and this is the seam that
        // fails when somebody adds a rung to one of them.
        assertThat(names(SlaPolicyDtos.Source.values()))
                .containsExactly(names(SlaResolution.Source.values()));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String[] names(Enum<?>[] values) {
        return java.util.Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }

    private static SlaPolicyDtos.SlaCell cell(List<SlaPolicyDtos.SlaCell> cells, int taskTypeId, String level) {
        return cells.stream()
                .filter(c -> c.taskTypeId() == taskTypeId && c.level().equals(level))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no cell for " + taskTypeId + "/" + level));
    }

    private static SlaPolicyDtos.SlaPolicyWrite override(int taskTypeId, String level,
                                                         double responseHrs, double resolutionHrs) {
        return new SlaPolicyDtos.SlaPolicyWrite(taskTypeId, level,
                BigDecimal.valueOf(responseHrs), BigDecimal.valueOf(resolutionHrs), true, false);
    }

    private void insertPolicy(Long project, Integer taskType, String level,
                              double responseHrs, double resolutionHrs) {
        jdbc.update("""
                INSERT INTO sla_policies (project_id, task_type_id, level, response_hrs, resolution_hrs)
                VALUES (?, ?, ?, ?, ?)
                """, project, taskType, level, responseHrs, resolutionHrs);
    }

    private long overrideId(int taskTypeId, String level) {
        return jdbc.queryForObject("""
                SELECT id FROM sla_policies
                 WHERE project_id = ? AND task_type_id = ? AND level = ?
                """, Long.class, projectId, taskTypeId, level);
    }

    private boolean rowExists(long policyId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sla_policies WHERE id = ?", Integer.class, policyId) == 1;
    }

    private boolean isActive(long policyId) {
        return jdbc.queryForObject(
                "SELECT is_active FROM sla_policies WHERE id = ?", Boolean.class, policyId);
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
     * Org-wide rows are cleared too.
     *
     * <p>They have no fixture prefix to key on — {@code project_id IS NULL} is
     * the whole identity of a rung-3 row — so the sweep has to name them
     * explicitly. Nothing else in the suite creates one: the four
     * {@code ReferenceDataFixture} writes are behind the {@code fixtures}
     * profile, which is not active here.
     */
    private void clearFixtureRows() {
        jdbc.update("""
                DELETE FROM sla_policies
                 WHERE project_id IS NULL
                    OR project_id IN (SELECT id FROM projects WHERE project_code IN ('ITSL', 'ITSX'))
                """);
        jdbc.update("DELETE FROM projects WHERE project_code IN ('ITSL', 'ITSX')");
    }
}
