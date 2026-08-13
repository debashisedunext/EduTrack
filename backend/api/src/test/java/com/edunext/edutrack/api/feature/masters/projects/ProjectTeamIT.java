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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-017 · the Team tab against a real MySQL.
 *
 * <p>{@code ProjectMemberServiceTest} proves the decisions against mocks. This
 * proves the half a mock cannot:
 *
 * <ul>
 *   <li>that {@code ck_project_members_allocation} really refuses 101 and really
 *       admits NULL;</li>
 *   <li>that {@code uq_project_members} makes the add path an upsert, so a
 *       removal is reversible rather than permanent;</li>
 *   <li>that "open" resolves through {@code statuses.is_open} rather than a
 *       status literal, and counts tickets <b>on this project only</b>;</li>
 *   <li><b>that B-011's writer of the same table does not clear an allocation
 *       this screen set</b> — the one claim in the whole task that no amount of
 *       reading either file can settle, because it is about what two statements
 *       do to one row.</li>
 * </ul>
 *
 * <p>Fixture rows are prefixed {@code ITTM} so nothing collides with the seed or
 * with the project, resource and role suites, and the cleanup can be exact.
 */
@SpringBootTest
@Testcontainers
class ProjectTeamIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_project_team_it")
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
    ProjectMemberService service;

    @Autowired
    JdbcTemplate jdbc;

    private long projectId;
    private long devId;
    private long qaId;

    @BeforeEach
    void seed() {
        clearFixtureRows();

        jdbc.update("INSERT INTO projects (project_code, name) VALUES ('ITTM', 'Team tab fixture')");
        projectId = jdbc.queryForObject(
                "SELECT id FROM projects WHERE project_code = 'ITTM'", Long.class);

        devId = insertUser("ITTM001", "ittm.dev", "Ravi Menon", "DEVELOPER", true);
        qaId = insertUser("ITTM002", "ittm.qa", "Sneha Iyer", "QA", true);
    }

    @AfterEach
    void cleanUp() {
        clearFixtureRows();
    }

    private void clearFixtureRows() {
        jdbc.update("DELETE FROM tickets WHERE ticket_code LIKE 'ITTM%'");
        jdbc.update("DELETE FROM project_members WHERE project_id IN "
                + "(SELECT id FROM projects WHERE project_code LIKE 'ITTM%')");
        jdbc.update("DELETE FROM projects WHERE project_code LIKE 'ITTM%'");
        jdbc.update("DELETE FROM users WHERE emp_code LIKE 'ITTM%'");
    }

    // ------------------------------------------------------------------
    // the schema's own opinions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ck_project_members_allocation refuses 101 and admits NULL")
    void theAllocationRangeIsConstrained() {
        // NULL is the point of the CHECK's first branch: every row written
        // before B-017 has no allocation because no screen had an input for one,
        // and a NOT NULL column would have had to invent values for all of them.
        jdbc.update("INSERT INTO project_members (project_id, user_id, allocation_pct) VALUES (?, ?, NULL)",
                projectId, devId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO project_members (project_id, user_id, allocation_pct) VALUES (?, ?, 101)",
                projectId, qaId))
                .hasMessageContaining("ck_project_members_allocation");
    }

    @Test
    @DisplayName("ck_project_members_role still refuses ADMIN, which the contract used to permit")
    void adminIsNotAProjectRole() {
        // The contract's first draft of addProjectMember typed projectRole as
        // RoleCode. A request carrying ADMIN was well-formed by that contract
        // and could only ever have arrived as a 500 — this is the constraint
        // that would have produced it.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO project_members (project_id, user_id, role_in_project) VALUES (?, ?, 'ADMIN')",
                projectId, devId))
                .hasMessageContaining("ck_project_members_role");
    }

    // ------------------------------------------------------------------
    // the rules, end to end
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a member lands with their role, allocation and global role on the roster")
    void addThenRead() {
        service.add(projectId, write(devId, "QA", 40));

        List<ProjectMemberDtos.TeamMember> roster = service.roster(projectId);

        assertThat(roster).hasSize(1);
        ProjectMemberDtos.TeamMember member = roster.getFirst();
        assertThat(member.userId()).isEqualTo(devId);
        assertThat(member.displayName()).isEqualTo("Ravi Menon");
        // The two roles differ, which is the case the column exists for and the
        // reason the roster carries both.
        assertThat(member.role()).isEqualTo("DEVELOPER");
        assertThat(member.projectRole()).isEqualTo("QA");
        assertThat(member.allocationPct()).isEqualTo(40);
        assertThat(member.addedAt()).isNotNull();
    }

    @Test
    @DisplayName("an allocation left unstated is null on the way out, not zero and not 100")
    void unstatedAllocationSurvivesTheRoundTrip() {
        service.add(projectId, write(devId, null, null));

        assertThat(service.roster(projectId).getFirst().allocationPct()).isNull();
    }

    @Test
    @DisplayName("zero is stored and read back as zero")
    void zeroIsAValue() {
        // getInt() would answer 0 for a SQL NULL as well, collapsing "no
        // capacity committed" into "not stated". This is the test that fails if
        // the mapper is ever simplified to getInt.
        service.add(projectId, write(devId, null, 0));

        assertThat(service.roster(projectId).getFirst().allocationPct()).isZero();
    }

    @Test
    @DisplayName("adding somebody already on the team is a 409, and changes nothing")
    void duplicateIsRefused() {
        service.add(projectId, write(devId, "QA", 40));

        assertThatThrownBy(() -> service.add(projectId, write(devId, "PM", 100)))
                .isInstanceOf(ProjectMemberService.AlreadyOnTeamException.class);

        ProjectMemberDtos.TeamMember member = service.roster(projectId).getFirst();
        assertThat(member.projectRole()).isEqualTo("QA");
        assertThat(member.allocationPct()).isEqualTo(40);
    }

    @Test
    @DisplayName("removal is reversible: one row per pair, deactivated and brought back")
    void removalIsReversible() {
        service.add(projectId, write(devId, "QA", 40));
        service.remove(projectId, devId);

        assertThat(service.roster(projectId)).isEmpty();
        // Deactivated, not deleted — the row is the record that they were on the
        // project while the tickets assigned to them then were being worked.
        assertThat(rowCount(devId)).isEqualTo(1);

        service.add(projectId, write(devId, "PM", 60));

        assertThat(rowCount(devId)).as("uq_project_members means there is only ever one").isEqualTo(1);
        assertThat(service.roster(projectId)).hasSize(1);
        assertThat(service.roster(projectId).getFirst().projectRole()).isEqualTo("PM");
    }

    @Test
    @DisplayName("a patch clears the role without touching the allocation")
    void patchClearsOneFieldOnly() {
        service.add(projectId, write(devId, "QA", 40));

        Optional<ProjectMemberDtos.TeamMember> updated =
                service.update(projectId, devId, patch(Optional.empty(), null));

        assertThat(updated).isPresent();
        assertThat(updated.get().projectRole()).isNull();
        assertThat(updated.get().allocationPct()).as("omitted, so untouched").isEqualTo(40);
    }

    @Test
    @DisplayName("a patch cannot resurrect a membership somebody else removed")
    void patchDoesNotReactivate() {
        // UPDATE_MEMBER never names is_active, and its predicate requires it.
        // Without both, changing a percentage on a stale tab would put somebody
        // silently back on the team as a side effect.
        service.add(projectId, write(devId, "QA", 40));
        service.remove(projectId, devId);

        assertThat(service.update(projectId, devId, patch(null, Optional.of(90)))).isEmpty();
        assertThat(service.roster(projectId)).isEmpty();
    }

    // ------------------------------------------------------------------
    // open tickets
    // ------------------------------------------------------------------

    @Test
    @DisplayName("open is statuses.is_open, and a closed ticket does not block a removal")
    void closedTicketsDoNotBlock() {
        service.add(projectId, write(devId, "QA", 40));
        insertTicket("ITTM-26-00001", devId, "CLOSED");

        assertThat(service.roster(projectId).getFirst().openTicketCount()).isZero();

        service.remove(projectId, devId);
        assertThat(service.roster(projectId)).isEmpty();
    }

    @Test
    @DisplayName("an open ticket on this project blocks the removal, and the count is on the roster first")
    void openTicketsBlockRemoval() {
        service.add(projectId, write(devId, "QA", 40));
        insertTicket("ITTM-26-00002", devId, "IN_PROGRESS");

        // The number is on the roster before the click, which is what makes the
        // refusal predictable rather than a surprise — B-014's lesson from the
        // resource grid, applied here.
        assertThat(service.roster(projectId).getFirst().openTicketCount()).isEqualTo(1);

        assertThatThrownBy(() -> service.remove(projectId, devId))
                .isInstanceOf(ProjectMemberService.OpenTicketsException.class);

        assertThat(service.roster(projectId)).hasSize(1);
    }

    @Test
    @DisplayName("an open ticket on another project does not block the removal")
    void otherProjectsDoNotCount() {
        // The count is scoped to the project in the path. Without that, removing
        // somebody from one team would be refused because of work they hold
        // somewhere else entirely — which is not a fact this screen can act on.
        jdbc.update("INSERT INTO projects (project_code, name) VALUES ('ITTM2', 'Elsewhere')");
        long other = jdbc.queryForObject(
                "SELECT id FROM projects WHERE project_code = 'ITTM2'", Long.class);

        service.add(projectId, write(devId, "QA", 40));
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, level, original_level, status, assigned_to)
                VALUES ('ITTM-26-00003', ?, 'Elsewhere', 'MEDIUM', 'MEDIUM', 'IN_PROGRESS', ?)
                """, other, devId);

        assertThat(service.roster(projectId).getFirst().openTicketCount()).isZero();
        service.remove(projectId, devId);
        assertThat(service.roster(projectId)).isEmpty();
    }

    // ------------------------------------------------------------------
    // the other writer of this table
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a resource-form save does not clear an allocation the Team tab set")
    void aResourceFormSaveDoesNotClearAllocation() {
        // This is the claim the migration header makes and the reason
        // allocation_pct was added as a column rather than folded into B-011's
        // upsert. That statement is reproduced verbatim here rather than invoked
        // through ResourceWriteService, because the assertion is about the SQL:
        // it names role_in_project and is_active and must go on not naming
        // allocation_pct. If anybody widens it, this fails.
        service.add(projectId, write(devId, "QA", 40));

        jdbc.update("""
                INSERT INTO project_members (project_id, user_id, role_in_project, is_active)
                VALUES (?, ?, 'DEVELOPER', 1)
                ON DUPLICATE KEY UPDATE role_in_project = VALUES(role_in_project), is_active = 1
                """, projectId, devId);

        ProjectMemberDtos.TeamMember member = service.roster(projectId).getFirst();
        assertThat(member.projectRole()).as("the resource form did change the role").isEqualTo("DEVELOPER");
        assertThat(member.allocationPct()).as("and left the allocation alone").isEqualTo(40);
    }

    @Test
    @DisplayName("a membership B-011 wrote is a full member here, with a null allocation")
    void rowsWrittenBeforeThisScreenAreReadable() {
        // The corpus this screen inherits: B-007's fixtures and every B-011 save
        // have no allocation. They must render, not be filtered out or defaulted.
        jdbc.update("""
                INSERT INTO project_members (project_id, user_id, role_in_project, is_active)
                VALUES (?, ?, NULL, 1)
                """, projectId, qaId);

        ProjectMemberDtos.TeamMember member = service.roster(projectId).getFirst();
        assertThat(member.userId()).isEqualTo(qaId);
        assertThat(member.projectRole()).as("null means 'same as their global role'").isNull();
        assertThat(member.allocationPct()).isNull();
    }

    // ------------------------------------------------------------------
    // refusals that need a real row
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a deactivated resource cannot be added")
    void deactivatedResourceIsRefused() {
        long leaver = insertUser("ITTM003", "ittm.gone", "Arjun Nair", "DEVELOPER", false);

        assertThatThrownBy(() -> service.add(projectId, write(leaver, "QA", 40)))
                .isInstanceOf(ProjectMemberService.MemberValidationException.class)
                .hasMessageContaining("deactivated");
    }

    @Test
    @DisplayName("an unknown project is 404 rather than an empty team")
    void unknownProjectIsNotFound() {
        assertThatThrownBy(() -> service.roster(999_999L))
                .isInstanceOf(ProjectMemberService.NoSuchProjectException.class);
    }

    @Test
    @DisplayName("the roster is ordered by name")
    void rosterIsOrderedByName() {
        service.add(projectId, write(qaId, null, null));    // Sneha Iyer
        service.add(projectId, write(devId, null, null));   // Ravi Menon

        assertThat(service.roster(projectId))
                .extracting(ProjectMemberDtos.TeamMember::displayName)
                .containsExactly("Ravi Menon", "Sneha Iyer");
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private long insertUser(String empCode, String username, String fullName, String role, boolean active) {
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id, is_active)
                SELECT ?, ?, ?, 'x', ?, r.id, ?
                  FROM roles r WHERE r.code = ?
                """, empCode, username, username + "@example.test", fullName, active ? 1 : 0, role);
        return jdbc.queryForObject("SELECT id FROM users WHERE emp_code = ?", Long.class, empCode);
    }

    private void insertTicket(String code, long assignedTo, String status) {
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, level, original_level, status, assigned_to)
                VALUES (?, ?, 'Fixture', 'MEDIUM', 'MEDIUM', ?, ?)
                """, code, projectId, status, assignedTo);
    }

    private int rowCount(long userId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_members WHERE project_id = ? AND user_id = ?",
                Integer.class, projectId, userId);
    }

    private static ProjectMemberDtos.TeamMemberWrite write(long userId, String role, Integer allocation) {
        return new ProjectMemberDtos.TeamMemberWrite(userId, role, allocation);
    }

    private static ProjectMemberDtos.TeamMemberPatch patch(
            Optional<String> role, Optional<Integer> allocation) {
        return new ProjectMemberDtos.TeamMemberPatch(role, allocation);
    }
}
