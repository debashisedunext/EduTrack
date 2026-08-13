package com.edunext.edutrack.api.feature.masters.projects;

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

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-016 · S-10 against a real MySQL.
 *
 * <p>{@code ProjectServiceTest} proves the decisions against mocks. This proves
 * the half a mock cannot: that {@code uq_projects_code} really is
 * case-insensitive under {@code utf8mb4_0900_ai_ci}, that
 * {@code ck_projects_status} refuses the two values the entity's javadoc used to
 * name, that the keyset cursor pages without skipping or repeating a row, and
 * that {@code ticket_seq} survives a save — the lost-update race
 * {@code Project.ticketSeq} warns about, reproduced from this screen's side.
 *
 * <p>Fixture rows are prefixed {@code ITPRJ} so nothing collides with the seed or
 * with the resource and role suites, and the cleanup can be exact.
 */
@SpringBootTest
@Testcontainers
class ProjectMasterIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_project_master_it")
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
    ProjectService service;

    @Autowired
    JdbcTemplate jdbc;

    private long managerId;

    @BeforeEach
    void seedManager() {
        clearFixtureRows();
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id, is_active)
                SELECT 'ITPRJ001', 'itprj.pm', 'itprj.pm@example.test', 'x', 'ITPRJ Manager', r.id, 1
                  FROM roles r WHERE r.code = 'PM'
                """);
        managerId = jdbc.queryForObject(
                "SELECT id FROM users WHERE emp_code = 'ITPRJ001'", Long.class);
    }

    @AfterEach
    void cleanUp() {
        clearFixtureRows();
    }

    private void clearFixtureRows() {
        jdbc.update("DELETE FROM project_members WHERE project_id IN "
                + "(SELECT id FROM projects WHERE project_code LIKE 'ITP%')");
        jdbc.update("DELETE FROM projects WHERE project_code LIKE 'ITP%'");
        jdbc.update("DELETE FROM users WHERE emp_code LIKE 'ITPRJ%'");
    }

    // ------------------------------------------------------------------
    // the schema's own opinions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ck_projects_status refuses the two values the javadoc used to name")
    void theStatusVocabularyIsConstrained() {
        // The entity described "ACTIVE | ON_HOLD | COMPLETED | ARCHIVED"; the
        // blueprint names three. Two vocabularies for one column with nothing
        // mapping between them is what B-030 found on import_batches.status —
        // B-016 is this column's first writer, so the constraint lands before
        // anything can write the other two rather than after.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO projects (project_code, name, status) VALUES ('ITPBAD', 'Bad', 'ARCHIVED')"))
                .hasMessageContaining("ck_projects_status");
    }

    @Test
    @DisplayName("uq_projects_code collides case-insensitively, and the service agrees")
    void theDuplicateCheckAgreesWithTheIndex() {
        // The check is the good error message; the index is the thing that is
        // actually true. If the two disagreed on case, `itpcrm` would pass the
        // check and then die at the index as a 500.
        service.create(write("ITPCRM", "CRM"));

        assertThatThrownBy(() -> service.create(write("itpcrm", "CRM again")))
                .isInstanceOf(ProjectService.DuplicateCodeException.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM projects WHERE project_code LIKE 'ITPCRM'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the row lands with every S-10 field, description and rule included")
    void everyFieldRoundTrips() {
        // The two columns V20260813_1420 added are the point: before it, S-10's
        // Description had nowhere to go and autoAssignRule was a contract field
        // with no storage behind it.
        ProjectDtos.ProjectWrite write = new ProjectDtos.ProjectWrite(
                "ITPFULL", "Full Project", "Acme Retail Ltd", "Every field set",
                managerId, "#06B6D4",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 12, 18),
                "ON_HOLD", "LEAST_LOADED");

        ProjectDtos.ProjectDetail created = service.create(write);

        assertThat(created.projectCode()).isEqualTo("ITPFULL");
        assertThat(created.clientName()).isEqualTo("Acme Retail Ltd");
        assertThat(created.description()).isEqualTo("Every field set");
        assertThat(created.colourTag()).isEqualTo("#06B6D4");
        assertThat(created.startDate()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(created.endDate()).isEqualTo(LocalDate.of(2026, 12, 18));
        assertThat(created.status()).isEqualTo("ON_HOLD");
        assertThat(created.autoAssignRule()).isEqualTo("LEAST_LOADED");
        assertThat(created.ticketsIssued()).isZero();
        // The derivation, end to end: On Hold is not Closed.
        assertThat(created.isActive()).isTrue();
        assertThat(created.projectManager()).isNotNull();
        assertThat(created.projectManager().displayName()).isEqualTo("ITPRJ Manager");
    }

    // ------------------------------------------------------------------
    // the immutability rule, against the real counter
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the code is editable until ticket_seq moves, and refused afterwards")
    void ticketSeqIsWhatClosesTheCode() {
        long id = service.create(write("ITPONE", "Renameable")).id();

        service.update(id, patchCode("ITPTWO"));
        assertThat(service.find(id).orElseThrow().projectCode()).isEqualTo("ITPTWO");

        // Exactly what PLAN.md §3.2's allocation does, and the only thing that
        // moves this counter. Not "insert a ticket": the rule is about codes
        // ISSUED, and a ticket row is neither necessary nor sufficient.
        jdbc.update("UPDATE projects SET ticket_seq = LAST_INSERT_ID(ticket_seq + 1) WHERE id = ?", id);

        assertThatThrownBy(() -> service.update(id, patchCode("ITPTHREE")))
                .isInstanceOf(ProjectService.ImmutableCodeException.class);

        assertThat(service.find(id).orElseThrow().projectCode()).isEqualTo("ITPTWO");
    }

    @Test
    @DisplayName("an ordinary save does not revert a ticket-ID allocation")
    void savingDoesNotRollBackTheCounter() {
        // Project.ticketSeq's javadoc: Hibernate writes every updatable column
        // of a dirty entity, so a rename flushed after a concurrent allocation
        // would restore the pre-increment value and the next ticket would reuse
        // the ID. ProjectMasterRepository.UPDATE names its columns and ticket_seq is
        // not among them; this is that claim, against the real table.
        long id = service.create(write("ITPSEQ", "Busy Project")).id();
        jdbc.update("UPDATE projects SET ticket_seq = LAST_INSERT_ID(ticket_seq + 1) WHERE id = ?", id);
        jdbc.update("UPDATE projects SET ticket_seq = LAST_INSERT_ID(ticket_seq + 1) WHERE id = ?", id);

        service.update(id, new ProjectDtos.ProjectPatch(
                null, "Busy Project, renamed", null, null, null, null, null, null, "ON_HOLD", null));

        assertThat(jdbc.queryForObject(
                "SELECT ticket_seq FROM projects WHERE id = ?", Long.class, id))
                .isEqualTo(2L);
        assertThat(service.find(id).orElseThrow().ticketsIssued()).isEqualTo(2L);
    }

    // ------------------------------------------------------------------
    // paging and filtering
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the keyset cursor pages without skipping or repeating a row")
    void theCursorPagesCleanly() {
        // The property offset paging does not have: a row inserted while
        // somebody is on page 2 shifts one row to page 3 unseen.
        for (int i = 1; i <= 5; i++) {
            service.create(write("ITPP" + i, "ITP Paged " + i));
        }
        ProjectMasterRepository.ProjectFilter mine = filter("ITP Paged");

        List<ProjectDtos.Project> first = service.page(mine, null, 2);
        assertThat(first).hasSize(2);

        ProjectDtos.Project last = first.get(1);
        List<ProjectDtos.Project> second =
                service.page(mine, new ProjectCursor(last.name(), last.id()), 2);

        assertThat(second).hasSize(2);
        assertThat(second).extracting(ProjectDtos.Project::id)
                .doesNotContainAnyElementsOf(first.stream().map(ProjectDtos.Project::id).toList());
        assertThat(first.get(0).name()).isEqualTo("ITP Paged 1");
        assertThat(second.get(0).name()).isEqualTo("ITP Paged 3");
    }

    @Test
    @DisplayName("?isActive=true keeps On Hold and drops Closed")
    void theActiveFilterFollowsTheDerivation() {
        // The predicate the five existing pickers depend on. If this filtered
        // on status = 'ACTIVE', an On Hold project would silently vanish from
        // the create-ticket form.
        service.create(withStatus("ITPACT", "ITP Live", "ACTIVE"));
        service.create(withStatus("ITPHLD", "ITP Paused", "ON_HOLD"));
        service.create(withStatus("ITPCLS", "ITP Retired", "CLOSED"));

        List<ProjectDtos.Project> active = service.page(
                new ProjectMasterRepository.ProjectFilter(true, null, null, "ITP "), null, 50);

        assertThat(active).extracting(ProjectDtos.Project::projectCode)
                .contains("ITPACT", "ITPHLD")
                .doesNotContain("ITPCLS");
    }

    @Test
    @DisplayName("?q= matches the code as well as the name")
    void theSearchCoversBothColumns() {
        service.create(write("ITPFIND", "Nothing Alike"));

        assertThat(service.page(filter("ITPFIND"), null, 50))
                .extracting(ProjectDtos.Project::projectCode).containsExactly("ITPFIND");
        assertThat(service.page(filter("Nothing Alike"), null, 50))
                .extracting(ProjectDtos.Project::projectCode).containsExactly("ITPFIND");
    }

    @Test
    @DisplayName("?managerId= returns that resource's projects")
    void theManagerFilterWorks() {
        service.create(write("ITPMGR", "Managed"));

        assertThat(service.page(
                new ProjectMasterRepository.ProjectFilter(null, null, managerId, null), null, 50))
                .extracting(ProjectDtos.Project::projectCode).containsExactly("ITPMGR");
    }

    // ------------------------------------------------------------------
    // the manager
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a deactivated manager is refused, against the real users table")
    void aDeactivatedManagerIsRefused() {
        jdbc.update("UPDATE users SET is_active = 0 WHERE id = ?", managerId);

        assertThatThrownBy(() -> service.create(write("ITPDEAD", "Orphaned")))
                .isInstanceOf(ProjectService.ProjectValidationException.class)
                .hasMessageContaining("deactivated");
    }

    @Test
    @DisplayName("fk_projects_manager is never reached, because the service refuses first")
    void anUnknownManagerNeverReachesTheForeignKey() {
        // Without the check this would be a constraint violation naming an
        // index — a 500 where the caller needs a message on the picker.
        ProjectDtos.ProjectWrite write = new ProjectDtos.ProjectWrite(
                "ITPNOMGR", "No Such Manager", null, null, 9_999_999L,
                null, null, null, null, null);

        assertThatThrownBy(() -> service.create(write))
                .isInstanceOf(ProjectService.ProjectValidationException.class)
                .hasMessageContaining("no such resource");
    }

    // ------------------------------------------------------------------
    // patch semantics, against a stored row
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an omitted field keeps its stored value through a real round trip")
    void omittedFieldsSurvive() {
        long id = service.create(new ProjectDtos.ProjectWrite(
                "ITPKEEP", "Keeper", "Acme", "Original description", managerId,
                "#F59E0B", LocalDate.of(2026, 2, 1), null, "ACTIVE", "ROUND_ROBIN")).id();

        service.update(id, new ProjectDtos.ProjectPatch(
                null, "Keeper renamed", null, null, null, null, null, null, null, null));

        ProjectDtos.ProjectDetail after = service.find(id).orElseThrow();
        assertThat(after.name()).isEqualTo("Keeper renamed");
        assertThat(after.clientName()).isEqualTo("Acme");
        assertThat(after.description()).isEqualTo("Original description");
        assertThat(after.colourTag()).isEqualTo("#F59E0B");
        assertThat(after.startDate()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(after.autoAssignRule()).isEqualTo("ROUND_ROBIN");
    }

    @Test
    @DisplayName("an unknown project is empty rather than an error")
    void unknownProjectIsEmpty() {
        assertThat(service.find(9_999_999L)).isEmpty();
        assertThat(service.update(9_999_999L, patchCode("ITPX"))).isEmpty();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private ProjectDtos.ProjectWrite write(String code, String name) {
        return new ProjectDtos.ProjectWrite(
                code, name, null, null, managerId, null, null, null, null, null);
    }

    private ProjectDtos.ProjectWrite withStatus(String code, String name, String status) {
        return new ProjectDtos.ProjectWrite(
                code, name, null, null, managerId, null, null, null, status, null);
    }

    private static ProjectDtos.ProjectPatch patchCode(String code) {
        return new ProjectDtos.ProjectPatch(code, null, null, null, null, null, null, null, null, null);
    }

    private static ProjectMasterRepository.ProjectFilter filter(String query) {
        return new ProjectMasterRepository.ProjectFilter(null, null, null, query);
    }
}
