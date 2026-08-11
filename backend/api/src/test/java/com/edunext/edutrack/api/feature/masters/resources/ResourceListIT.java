package com.edunext.edutrack.api.feature.masters.resources;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-010 · the S-07 grid against a real MySQL.
 *
 * <p>{@code ResourceServiceTest} proves the paging arithmetic and the bulk
 * outcomes against a mocked repository. This proves the half a mock cannot: that
 * the SQL is valid, that the keyset predicate agrees with {@code ORDER BY} under
 * the actual collation, that {@code LIKE} escaping holds, and that the
 * batched lookups return what the grid renders.
 *
 * <p>Fixture rows are prefixed {@code ITRES} — employee codes, usernames and
 * emails alike — so nothing here collides with B-001's seed or another suite's
 * rows, and the cleanup can be exact.
 */
@SpringBootTest
@Testcontainers
class ResourceListIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_resource_it")
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
    ResourceService service;

    @Autowired
    ResourceRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    private long projectId;
    private long otherProjectId;
    private long managerId;

    @BeforeEach
    void seedFixtures() {
        clearFixtureRows();

        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES ('ITRES1', 'IT Resources One', 'ACTIVE')");
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES ('ITRES2', 'IT Resources Two', 'ACTIVE')");
        projectId = idOfProject("ITRES1");
        otherProjectId = idOfProject("ITRES2");

        managerId = insertUser("ITRES000", "Aarav Manager", "PM", "Leadership", true);

        // Two people with the same name, deliberately. A keyset over full_name
        // alone either skips or repeats them; this is the pair that shows it.
        insertUser("ITRES001", "Priya Sharma", "DEVELOPER", "Engineering", true);
        insertUser("ITRES002", "Priya Sharma", "QA", "Quality", true);
        insertUser("ITRES003", "Zoya Khan", "DEVELOPER", "Engineering", false);
        insertUser("ITRES004", "Bhavesh 100% Patel", "SUPPORT", "Support", true);
    }

    private void clearFixtureRows() {
        jdbc.update("DELETE FROM tickets WHERE ticket_code LIKE 'ITRES%'");
        jdbc.update("DELETE FROM project_members WHERE user_id IN (SELECT id FROM users WHERE emp_code LIKE 'ITRES%')");
        jdbc.update("UPDATE users SET reporting_manager_id = NULL WHERE emp_code LIKE 'ITRES%'");
        jdbc.update("DELETE FROM users WHERE emp_code LIKE 'ITRES%'");
        jdbc.update("DELETE FROM projects WHERE project_code LIKE 'ITRES%'");
    }

    // ------------------------------------------------------------------
    // filters
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("filters")
    class Filters {

        @Test
        @DisplayName("free text matches name, username, email and employee code")
        void searchSpansFourColumns() {
            assertThat(namesMatching(search("Zoya"))).containsExactly("Zoya Khan");
            assertThat(namesMatching(search("itres003"))).containsExactly("Zoya Khan");
            assertThat(namesMatching(search("ITRES003@"))).containsExactly("Zoya Khan");
            assertThat(namesMatching(search("ITRES003"))).containsExactly("Zoya Khan");
        }

        @Test
        @DisplayName("a literal % in the search term matches a percent sign, not everything")
        void likeWildcardsAreEscaped() {
            // Unescaped, "100%" matches every row in the table and the grid
            // silently claims the whole organisation is called Bhavesh.
            assertThat(namesMatching(search("100%"))).containsExactly("Bhavesh 100% Patel");
        }

        @Test
        @DisplayName("an underscore in the search term is a literal too")
        void underscoreIsEscaped() {
            assertThat(namesMatching(search("Zo_a"))).isEmpty();
        }

        @Test
        @DisplayName("filtering by role uses the role code, not the display name")
        void filterByRole() {
            List<String> names = namesMatching(
                    new ResourceFilter("ITRES", "DEVELOPER", null, null, null));

            assertThat(names).containsExactlyInAnyOrder("Priya Sharma", "Zoya Khan");
        }

        @Test
        @DisplayName("filtering by status returns only that side, and unset returns both")
        void filterByStatus() {
            assertThat(namesMatching(new ResourceFilter("ITRES", null, null, null, false)))
                    .containsExactly("Zoya Khan");
            assertThat(namesMatching(new ResourceFilter("ITRES", null, null, null, true)))
                    .hasSize(4);
            assertThat(namesMatching(new ResourceFilter("ITRES", null, null, null, null)))
                    .hasSize(5);
        }

        @Test
        @DisplayName("filtering by project reads active memberships only")
        void filterByProject() {
            long priya = idOfUser("ITRES001");
            long zoya = idOfUser("ITRES003");
            addMembership(priya, projectId, true);
            addMembership(zoya, projectId, false);          // former team member
            addMembership(zoya, otherProjectId, true);

            assertThat(namesMatching(new ResourceFilter("ITRES", null, projectId, null, null)))
                    .containsExactly("Priya Sharma");
        }

        @Test
        @DisplayName("filtering by manager returns that manager's direct reports")
        void filterByManager() {
            jdbc.update("UPDATE users SET reporting_manager_id = ? WHERE emp_code = 'ITRES003'", managerId);

            assertThat(namesMatching(new ResourceFilter("ITRES", null, null, managerId, null)))
                    .containsExactly("Zoya Khan");
        }
    }

    // ------------------------------------------------------------------
    // paging
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("keyset paging")
    class Paging {

        @Test
        @DisplayName("walking every page visits every resource exactly once")
        void pagingIsLosslessAcrossDuplicateNames() {
            // One row per page, over a set containing two identical names. A
            // keyset on full_name alone stalls here or skips one of the pair,
            // and both failures are silent.
            List<String> seen = new ArrayList<>();
            String cursor = null;

            for (int page = 0; page < 10; page++) {
                ResourceDtos.ResourceListResponse response =
                        service.list(new ResourceFilter("ITRES", null, null, null, null), cursor, 1);
                response.data().forEach(resource -> seen.add(resource.employeeCode()));

                if (!response.meta().hasMore()) {
                    break;
                }
                cursor = response.meta().nextCursor();
            }

            assertThat(seen).containsExactlyInAnyOrder(
                    "ITRES000", "ITRES001", "ITRES002", "ITRES003", "ITRES004");
        }

        @Test
        @DisplayName("rows come back in name order")
        void sortedByName() {
            List<String> names = namesMatching(new ResourceFilter("ITRES", null, null, null, null));

            assertThat(names).isSorted();
        }

        @Test
        @DisplayName("totalCount counts matches, not the page")
        void totalCountIgnoresThePage() {
            ResourceDtos.ResourceListResponse response =
                    service.list(new ResourceFilter("ITRES", null, null, null, null), null, 2);

            assertThat(response.data()).hasSize(2);
            assertThat(response.meta().totalCount()).isEqualTo(5L);
        }
    }

    // ------------------------------------------------------------------
    // hydration
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("what a row carries")
    class RowContents {

        @Test
        @DisplayName("every S-07 column is populated from the database")
        void carriesEveryColumn() {
            long priya = idOfUser("ITRES001");
            jdbc.update("UPDATE users SET reporting_manager_id = ?, last_login_at = '2026-08-01 06:30:00' "
                    + "WHERE id = ?", managerId, priya);
            addMembership(priya, projectId, true);

            ResourceDtos.Resource resource = one(search("ITRES001"));

            assertThat(resource.employeeCode()).isEqualTo("ITRES001");
            assertThat(resource.displayName()).isEqualTo("Priya Sharma");
            assertThat(resource.email()).isEqualTo("ITRES001@edunext.test");
            assertThat(resource.role()).isEqualTo("DEVELOPER");
            assertThat(resource.department()).isEqualTo("Engineering");
            assertThat(resource.reportingManager().displayName()).isEqualTo("Aarav Manager");
            assertThat(resource.reportingManager().role()).isEqualTo("PM");
            assertThat(resource.projects()).extracting(ResourceDtos.ProjectRef::projectCode)
                    .containsExactly("ITRES1");
            assertThat(resource.isActive()).isTrue();
            assertThat(resource.lastLoginAt()).isNotNull();
        }

        @Test
        @DisplayName("last login is read back as the instant it was stored, not shifted by the JVM zone")
        void lastLoginRoundTripsAsUtc() {
            // The bug B-023 already hit once: a DATETIME(6) read as a Timestamp
            // is reinterpreted in the JVM's default zone, and every timestamp
            // on the screen moves by the offset.
            jdbc.update("UPDATE users SET last_login_at = '2026-08-01 06:30:00' WHERE emp_code = 'ITRES001'");

            assertThat(one(search("ITRES001")).lastLoginAt())
                    .isEqualTo(java.time.Instant.parse("2026-08-01T06:30:00Z"));
        }

        @Test
        @DisplayName("someone who has never logged in reports null, not the epoch")
        void neverLoggedIn() {
            assertThat(one(search("ITRES002")).lastLoginAt()).isNull();
        }

        @Test
        @DisplayName("no manager means no reportingManager, rather than a ref to nobody")
        void noManager() {
            assertThat(one(search("ITRES004")).reportingManager()).isNull();
        }

        @Test
        @DisplayName("a resource on no project reports an empty list, not null")
        void noProjects() {
            ResourceDtos.Resource resource = one(search("ITRES002"));

            assertThat(resource.projects()).isEmpty();
            assertThat(resource.projectIds()).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // bulk status against real tickets
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("bulk status")
    class BulkStatus {

        @Test
        @DisplayName("an open ticket blocks deactivation; a closed one does not")
        void openTicketsBlockDeactivation() {
            long priya = idOfUser("ITRES001");
            long bhavesh = idOfUser("ITRES004");
            insertTicket("ITRES-26-00001", priya, "IN_PROGRESS");
            insertTicket("ITRES-26-00002", bhavesh, "CLOSED");

            ResourceDtos.BulkStatusData result = service.setStatus(
                    new ResourceDtos.BulkStatusRequest(List.of(priya, bhavesh), false, "team change"));

            assertThat(result.blocked()).isEqualTo(1);
            assertThat(result.changed()).isEqualTo(1);
            assertThat(isActive(priya)).isTrue();
            assertThat(isActive(bhavesh)).isFalse();
        }

        @Test
        @DisplayName("open is statuses.is_open, not a hardcoded 'not CLOSED'")
        void opennessComesFromTheStatusMaster() {
            // REOPENED is is_open = 1 in B-003's seed. A literal <> 'CLOSED'
            // would agree here by accident; what it would miss is the next
            // status an Admin adds through S-13.
            long priya = idOfUser("ITRES001");
            insertTicket("ITRES-26-00003", priya, "REOPENED");

            assertThat(repository.openTicketCounts(List.of(priya))).containsEntry(priya, 1);
        }

        @Test
        @DisplayName("the count is per assignee, not a total")
        void countIsPerAssignee() {
            long priya = idOfUser("ITRES001");
            long zoya = idOfUser("ITRES003");
            insertTicket("ITRES-26-00004", priya, "NEW");
            insertTicket("ITRES-26-00005", priya, "ON_HOLD");
            insertTicket("ITRES-26-00006", zoya, "NEW");

            assertThat(repository.openTicketCounts(List.of(priya, zoya)))
                    .containsEntry(priya, 2)
                    .containsEntry(zoya, 1);
        }

        @Test
        @DisplayName("reactivating a resource is allowed however many tickets they hold")
        void reactivationIsNeverBlocked() {
            long zoya = idOfUser("ITRES003");
            insertTicket("ITRES-26-00007", zoya, "IN_PROGRESS");

            ResourceDtos.BulkStatusData result = service.setStatus(
                    new ResourceDtos.BulkStatusRequest(List.of(zoya), true, null));

            assertThat(result.changed()).isEqualTo(1);
            assertThat(isActive(zoya)).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // fixtures and helpers
    // ------------------------------------------------------------------

    private long insertUser(String empCode, String fullName, String roleCode,
                            String department, boolean isActive) {
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                   role_id, department, designation, is_active)
                VALUES (?, ?, ?, 'not-a-real-hash', ?,
                        (SELECT id FROM roles WHERE code = ?), ?, 'Fixture', ?)
                """,
                empCode, empCode.toLowerCase(java.util.Locale.ROOT), empCode + "@edunext.test",
                fullName, roleCode, department, isActive);
        return idOfUser(empCode);
    }

    private void insertTicket(String ticketCode, long assignedTo, String status) {
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, level, original_level,
                                     status, assigned_to)
                VALUES (?, ?, 'Fixture ticket', 'MEDIUM', 'MEDIUM', ?, ?)
                """, ticketCode, projectId, status, assignedTo);
    }

    private void addMembership(long userId, long project, boolean isActive) {
        jdbc.update("INSERT INTO project_members (project_id, user_id, is_active) VALUES (?, ?, ?)",
                project, userId, isActive);
    }

    private long idOfUser(String empCode) {
        return jdbc.queryForObject("SELECT id FROM users WHERE emp_code = ?", Long.class, empCode);
    }

    private long idOfProject(String projectCode) {
        return jdbc.queryForObject("SELECT id FROM projects WHERE project_code = ?", Long.class, projectCode);
    }

    private boolean isActive(long userId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT is_active FROM users WHERE id = ?", Boolean.class, userId));
    }

    private static ResourceFilter search(String q) {
        return new ResourceFilter(q, null, null, null, null);
    }

    /** Names on the first page, at the contract's default limit. */
    private List<String> namesMatching(ResourceFilter filter) {
        return service.list(filter, null, null).data().stream()
                .map(ResourceDtos.Resource::displayName)
                .toList();
    }

    private ResourceDtos.Resource one(ResourceFilter filter) {
        List<ResourceDtos.Resource> found = service.list(filter, null, null).data();
        assertThat(found).hasSize(1);
        return found.getFirst();
    }
}
