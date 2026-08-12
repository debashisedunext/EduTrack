package com.edunext.edutrack.api.feature.masters.resources;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-011 · the S-08 form against a real MySQL.
 *
 * <p>{@code ResourceWriteServiceTest} proves the decisions against mocks. This
 * proves the half a mock cannot: that the SQL is valid, that a partial update
 * really leaves the untouched columns alone, that the new {@code CHECK}
 * constraints reject what they are supposed to, that the {@code JSON} columns
 * round-trip, and that {@code DATE} and {@code DECIMAL(4,2)} survive the driver.
 *
 * <p>Fixture rows are prefixed {@code ITFRM} — employee codes, usernames and
 * emails alike — so nothing collides with B-001's seed or {@code ResourceListIT},
 * and the cleanup can be exact.
 */
@SpringBootTest
@Testcontainers
class ResourceFormIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_resource_form_it")
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
    ResourceWriteService service;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    private long projectOneId;
    private long projectTwoId;
    private long managerId;

    @BeforeEach
    void seedFixtures() {
        clearFixtureRows();

        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES ('ITFRM1', 'Form Project One', 'ACTIVE')");
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES ('ITFRM2', 'Form Project Two', 'ACTIVE')");
        projectOneId = idOfProject("ITFRM1");
        projectTwoId = idOfProject("ITFRM2");

        managerId = service.create(request("ITFRM000", "itfrm.manager", "PM")).resource().id();
    }

    private void clearFixtureRows() {
        jdbc.update("DELETE FROM tickets WHERE ticket_code LIKE 'ITFRM%'");
        jdbc.update("DELETE FROM project_members WHERE user_id IN (SELECT id FROM users WHERE emp_code LIKE 'ITFRM%')");
        jdbc.update("UPDATE users SET reporting_manager_id = NULL WHERE emp_code LIKE 'ITFRM%'");
        jdbc.update("DELETE FROM password_history WHERE user_id IN (SELECT id FROM users WHERE emp_code LIKE 'ITFRM%')");
        jdbc.update("DELETE FROM users WHERE emp_code LIKE 'ITFRM%'");
        jdbc.update("DELETE FROM projects WHERE project_code LIKE 'ITFRM%'");
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("every S-08 section round-trips through the database unchanged")
        void everySectionRoundTrips() {
            ResourceDtos.ResourceWriteRequest request = request("ITFRM001", "itfrm.full", "DEVELOPER");
            request.setMobile(Optional.of("+91 98765 43210"));
            request.setDateOfJoining(Optional.of(LocalDate.of(2026, 3, 17)));
            request.setAvatarUrl(Optional.of("https://files.edunext.test/avatars/itfrm001.png"));
            request.setDepartment(Optional.of("Engineering"));
            request.setDesignation(Optional.of("Senior Developer"));
            request.setLocation(Optional.of("Pune"));
            request.setTimezone(Optional.of("Asia/Kolkata"));
            request.setReportingManagerId(Optional.of(managerId));
            request.setDailyCapacityHrs(Optional.of(new BigDecimal("6.50")));
            request.setWeeklyOff(Optional.of(List.of(7, 6)));
            request.setSkills(Optional.of(List.of("Java", "React")));
            request.setProjects(Optional.of(List.of(
                    new ResourceDtos.ProjectAssignment(projectOneId, "DEVELOPER"),
                    new ResourceDtos.ProjectAssignment(projectTwoId, "QA"))));

            ResourceDtos.ResourceDetail saved = service.detail(service.create(request).resource().id());

            assertThat(saved.mobile()).isEqualTo("+91 98765 43210");
            // A DATE, not a DATETIME — 17 March must not become 16 March
            // because a timezone got involved on the way through the driver.
            assertThat(saved.dateOfJoining()).isEqualTo(LocalDate.of(2026, 3, 17));
            assertThat(saved.avatarUrl()).endsWith("itfrm001.png");
            assertThat(saved.department()).isEqualTo("Engineering");
            assertThat(saved.designation()).isEqualTo("Senior Developer");
            assertThat(saved.location()).isEqualTo("Pune");
            assertThat(saved.timezone()).isEqualTo("Asia/Kolkata");
            assertThat(saved.reportingManager()).isNotNull();
            assertThat(saved.reportingManager().id()).isEqualTo(managerId);
            assertThat(saved.dailyCapacityHrs()).isEqualByComparingTo("6.50");
            assertThat(saved.weeklyOff()).containsExactly(6, 7);
            assertThat(saved.skills()).containsExactly("Java", "React");
            assertThat(saved.projectAssignments()).containsExactlyInAnyOrder(
                    new ResourceDtos.ProjectAssignment(projectOneId, "DEVELOPER"),
                    new ResourceDtos.ProjectAssignment(projectTwoId, "QA"));
            assertThat(saved.mustChangePassword()).isTrue();
        }

        @Test
        @DisplayName("the stored hash verifies against the returned password, and is not the password")
        void passwordIsHashedNotStored() {
            ResourceWriteService.Created created = service.create(request("ITFRM002", "itfrm.pass", "QA"));

            String storedHash = jdbc.queryForObject(
                    "SELECT password_hash FROM users WHERE id = ?", String.class, created.resource().id());

            assertThat(storedHash).isNotEqualTo(created.temporaryPassword());
            assertThat(passwordEncoder.matches(created.temporaryPassword(), storedHash)).isTrue();
            // A-020's encoder, not Spring Security's bcrypt default —
            // PasswordEncoderConfig names Stream B's Resource Master as one of
            // the callers that would silently get the wrong algorithm.
            assertThat(storedHash).startsWith("$argon2id$");
        }

        @Test
        @DisplayName("a resource created with no weekly-off override stores NULL, meaning 'inherit the org week'")
        void weeklyOffDefaultsToNullNotEmpty() {
            long id = service.create(request("ITFRM003", "itfrm.inherit", "SUPPORT")).resource().id();

            assertThat(jdbc.queryForObject(
                    "SELECT weekly_off FROM users WHERE id = ?", String.class, id)).isNull();
            assertThat(service.detail(id).weeklyOff()).isNull();
        }

        @Test
        @DisplayName("a duplicate username is a DuplicateResourceException naming the field")
        void duplicateUsernameIsNamed() {
            service.create(request("ITFRM004", "itfrm.taken", "DEVELOPER"));

            // A different employee code AND a different email, so the only
            // thing that collides is the username. `request` derives the email
            // from the username, which would otherwise make this assert two
            // conflicts while claiming to test one.
            ResourceDtos.ResourceWriteRequest clash = request("ITFRM005", "itfrm.taken", "DEVELOPER");
            clash.setEmail("itfrm.different@edunext.test");

            assertThatThrownBy(() -> service.create(clash))
                    .isInstanceOf(ResourceWriteService.DuplicateResourceException.class)
                    .satisfies(e -> assertThat(
                            ((ResourceWriteService.DuplicateResourceException) e).fieldErrors())
                            .containsOnlyKeys("username"));
        }

        /**
         * B-013 · the check has to agree with the index, and the index is
         * case-insensitive.
         *
         * <p>{@code uq_users_username} is over a {@code utf8mb4_0900_ai_ci}
         * column, so {@code ITFRM.CASED} and {@code itfrm.cased} are one
         * username as far as MySQL is concerned. If the service check were
         * case-<i>sensitive</i> it would pass the second create, and the row
         * would then be refused by the index — which arrives as a
         * {@code DuplicateKeyException} with a constraint name in it rather than
         * the field-keyed 409 the form knows how to display. Same refusal
         * either way, but only one of them puts the message on the input.
         *
         * <p>Exercised against a real container rather than a mock precisely
         * because the claim is about the collation. A unit test would be
         * asserting Java's {@code equalsIgnoreCase} against itself.
         */
        @Test
        @DisplayName("a username differing only in case is the same username, as the index sees it")
        void duplicateDetectionIsCaseInsensitive() {
            service.create(request("ITFRM006", "itfrm.cased", "DEVELOPER"));

            ResourceDtos.ResourceWriteRequest shouted = request("ITFRM007", "ITFRM.CASED", "DEVELOPER");
            shouted.setEmail("itfrm.shouted@edunext.test");

            assertThatThrownBy(() -> service.create(shouted))
                    .isInstanceOf(ResourceWriteService.DuplicateResourceException.class)
                    .satisfies(e -> assertThat(
                            ((ResourceWriteService.DuplicateResourceException) e).fieldErrors())
                            .containsOnlyKeys("username"));
        }

        /**
         * B-013 · all three at once, against the database rather than a stubbed
         * {@code Conflicts}.
         *
         * <p>The reason the query names all three instead of stopping at the
         * first: an admin who fixes the username and resubmits should not then be
         * told about the email, and again about the employee code. One round of
         * correction rather than three.
         */
        @Test
        @DisplayName("a create colliding on all three fields names all three")
        void everyCollidingFieldIsNamedAtOnce() {
            service.create(request("ITFRM008", "itfrm.triple", "DEVELOPER"));

            assertThatThrownBy(() -> service.create(request("ITFRM008", "itfrm.triple", "DEVELOPER")))
                    .isInstanceOf(ResourceWriteService.DuplicateResourceException.class)
                    .satisfies(e -> assertThat(
                            ((ResourceWriteService.DuplicateResourceException) e).fieldErrors())
                            .containsOnlyKeys("username", "email", "employeeCode"));
        }
    }

    // ------------------------------------------------------------------
    // update
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("update")
    class Update {

        private long userId;

        @BeforeEach
        void createSubject() {
            ResourceDtos.ResourceWriteRequest request = request("ITFRM010", "itfrm.subject", "DEVELOPER");
            request.setDepartment(Optional.of("Engineering"));
            request.setLocation(Optional.of("Pune"));
            request.setSkills(Optional.of(List.of("Java")));
            userId = service.create(request).resource().id();
        }

        /**
         * The one a mock cannot prove. {@code ResourceWriteServiceTest} shows
         * the column is absent from the change map; this shows the value is
         * still in the row afterwards.
         */
        @Test
        @DisplayName("a field the PATCH did not mention still holds its value afterwards")
        void absentFieldsSurviveTheUpdate() {
            ResourceDtos.ResourceWriteRequest patch = request("ITFRM010", "itfrm.subject", "DEVELOPER");
            patch.setDepartment(Optional.of("Platform"));

            service.update(service.detail(userId), patch);

            ResourceDtos.ResourceDetail after = service.detail(userId);
            assertThat(after.department()).isEqualTo("Platform");
            assertThat(after.location()).isEqualTo("Pune");
            assertThat(after.skills()).containsExactly("Java");
        }

        /**
         * B-013 · a resource does not collide with themselves.
         *
         * <p>{@code FIND_CONFLICTS} carries {@code u.id <> ?} for exactly this,
         * and until now nothing asserted it. Every edit that leaves the three
         * unique fields alone — which is nearly every edit, since S-08 sends the
         * whole form back — re-submits the resource's own username, email and
         * employee code. Without the exclusion the very first "change the
         * department and save" would be refused with "that username is already
         * taken", naming the row being edited.
         *
         * <p>Sits here rather than in {@code ResourceWriteServiceTest} because a
         * mocked {@code findConflicts} answers whatever it was told to; the
         * exclusion is in the SQL, so only the database can be asked.
         */
        @Test
        @DisplayName("re-submitting a resource's own username, email and emp code is not a conflict")
        void aResourceDoesNotCollideWithItself() {
            ResourceDtos.ResourceWriteRequest unchanged =
                    request("ITFRM010", "itfrm.subject", "DEVELOPER");
            unchanged.setDepartment(Optional.of("Platform"));

            assertThat(service.update(service.detail(userId), unchanged).department())
                    .isEqualTo("Platform");
        }

        /**
         * B-013 · the exclusion is for the edited row only, not a way past the
         * check. Somebody else's username is still taken.
         */
        @Test
        @DisplayName("editing onto another resource's username is still a conflict")
        void anotherResourcesUsernameIsStillTaken() {
            service.create(request("ITFRM011", "itfrm.occupied", "DEVELOPER"));

            ResourceDtos.ResourceWriteRequest steal =
                    request("ITFRM010", "itfrm.occupied", "DEVELOPER");
            // Email follows the username in `request`, so without this the test
            // would assert two conflicts while claiming to be about one.
            steal.setEmail("itfrm.subject@edunext.test");

            ResourceDtos.ResourceDetail current = service.detail(userId);
            assertThatThrownBy(() -> service.update(current, steal))
                    .isInstanceOf(ResourceWriteService.DuplicateResourceException.class)
                    .satisfies(e -> assertThat(
                            ((ResourceWriteService.DuplicateResourceException) e).fieldErrors())
                            .containsOnlyKeys("username"));
        }

        @Test
        @DisplayName("an explicit null clears the column in the row, not just in the change map")
        void explicitNullClearsInTheDatabase() {
            ResourceDtos.ResourceWriteRequest patch = request("ITFRM010", "itfrm.subject", "DEVELOPER");
            patch.setLocation(Optional.empty());

            service.update(service.detail(userId), patch);

            assertThat(service.detail(userId).location()).isNull();
        }

        @Test
        @DisplayName("the role change lands as a role_id, resolved from the code")
        void roleChangeResolvesToAnId() {
            ResourceDtos.ResourceWriteRequest patch = request("ITFRM010", "itfrm.subject", "QA");

            service.update(service.detail(userId), patch);

            assertThat(service.detail(userId).role()).isEqualTo("QA");
        }

        @Test
        @DisplayName("a project dropped from the list is deactivated, not deleted — the history survives")
        void droppedMembershipIsDeactivated() {
            ResourceDtos.ResourceWriteRequest join = request("ITFRM010", "itfrm.subject", "DEVELOPER");
            join.setProjects(Optional.of(List.of(
                    new ResourceDtos.ProjectAssignment(projectOneId, "DEVELOPER"),
                    new ResourceDtos.ProjectAssignment(projectTwoId, "QA"))));
            service.update(service.detail(userId), join);

            ResourceDtos.ResourceWriteRequest leave = request("ITFRM010", "itfrm.subject", "DEVELOPER");
            leave.setProjects(Optional.of(List.of(
                    new ResourceDtos.ProjectAssignment(projectOneId, "DEVELOPER"))));
            service.update(service.detail(userId), leave);

            assertThat(service.detail(userId).projectAssignments())
                    .containsExactly(new ResourceDtos.ProjectAssignment(projectOneId, "DEVELOPER"));

            // The row is still there, holding the record that they were on it.
            Integer stillThere = jdbc.queryForObject(
                    "SELECT is_active FROM project_members WHERE user_id = ? AND project_id = ?",
                    Integer.class, userId, projectTwoId);
            assertThat(stillThere).isZero();
        }

        @Test
        @DisplayName("rejoining a project they left reactivates the row rather than colliding with it")
        void rejoiningReactivates() {
            ResourceDtos.ResourceWriteRequest join = request("ITFRM010", "itfrm.subject", "DEVELOPER");
            join.setProjects(Optional.of(List.of(
                    new ResourceDtos.ProjectAssignment(projectOneId, "DEVELOPER"))));
            service.update(service.detail(userId), join);

            ResourceDtos.ResourceWriteRequest leave = request("ITFRM010", "itfrm.subject", "DEVELOPER");
            leave.setProjects(Optional.of(List.of()));
            service.update(service.detail(userId), leave);

            ResourceDtos.ResourceWriteRequest rejoin = request("ITFRM010", "itfrm.subject", "DEVELOPER");
            rejoin.setProjects(Optional.of(List.of(
                    new ResourceDtos.ProjectAssignment(projectOneId, "QA"))));
            service.update(service.detail(userId), rejoin);

            assertThat(service.detail(userId).projectAssignments())
                    .containsExactly(new ResourceDtos.ProjectAssignment(projectOneId, "QA"));
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM project_members WHERE user_id = ? AND project_id = ?",
                    Integer.class, userId, projectOneId)).isEqualTo(1);
        }

        @Test
        @DisplayName("the ETag moves when a field changes and holds still when nothing does")
        void etagTracksContent() {
            String before = service.detail(userId).etag();

            ResourceDtos.ResourceWriteRequest noop = request("ITFRM010", "itfrm.subject", "DEVELOPER");
            service.update(service.detail(userId), noop);
            assertThat(service.detail(userId).etag()).isEqualTo(before);

            ResourceDtos.ResourceWriteRequest real = request("ITFRM010", "itfrm.subject", "DEVELOPER");
            real.setDesignation(Optional.of("Tech Lead"));
            service.update(service.detail(userId), real);
            assertThat(service.detail(userId).etag()).isNotEqualTo(before);
        }
    }

    // ------------------------------------------------------------------
    // the new constraints
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the constraints V20260811_1520 adds")
    class Constraints {

        /**
         * B-023's note records what the alternative day numbering cost: a `0`
         * read as ISO makes Sunday a working day and every weekend-spanning SLA
         * short by a day. The org calendar already refuses it; this proves the
         * per-resource override does too, so the defect cannot come back one
         * row at a time.
         */
        @Test
        @DisplayName("a weekly_off of 0 is refused by the database, not merely by Bean Validation")
        void weeklyOffZeroIsRefusedAtTheColumn() {
            long id = service.create(request("ITFRM020", "itfrm.days", "DEVELOPER")).resource().id();

            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE users SET weekly_off = CAST('[0, 6]' AS JSON) WHERE id = ?", id))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("ck_users_weekly_off");
        }

        @Test
        @DisplayName("a seven-day weekend is refused — addWorkingHours would never find a working day")
        void sevenDayWeekendIsRefused() {
            long id = service.create(request("ITFRM021", "itfrm.week", "DEVELOPER")).resource().id();

            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE users SET weekly_off = CAST('[1,2,3,4,5,6,7]' AS JSON) WHERE id = ?", id))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("ck_users_weekly_off");
        }

        @Test
        @DisplayName("an unrecognised project role is refused by ck_project_members_role")
        void unknownProjectRoleIsRefused() {
            long id = service.create(request("ITFRM022", "itfrm.role", "DEVELOPER")).resource().id();

            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO project_members (project_id, user_id, role_in_project) VALUES (?, ?, 'ARCHITECT')",
                    projectOneId, id))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("ck_project_members_role");
        }

        /**
         * VIEWER is a project role and not a global one — the whole reason
         * {@code ProjectRoleCode} is a separate enum from {@code RoleCode}.
         */
        @Test
        @DisplayName("VIEWER is a legal project role even though it is not a global one")
        void viewerIsALegalProjectRole() {
            ResourceDtos.ResourceWriteRequest request = request("ITFRM023", "itfrm.viewer", "DEVELOPER");
            request.setProjects(Optional.of(List.of(
                    new ResourceDtos.ProjectAssignment(projectOneId, "VIEWER"))));

            long id = service.create(request).resource().id();

            assertThat(service.detail(id).projectAssignments())
                    .containsExactly(new ResourceDtos.ProjectAssignment(projectOneId, "VIEWER"));
        }

        @Test
        @DisplayName("the self-reference trigger is still the backstop under the service check")
        void selfReferenceIsRefusedAtTheDatabaseToo() {
            long id = service.create(request("ITFRM024", "itfrm.self", "DEVELOPER")).resource().id();

            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE users SET reporting_manager_id = ? WHERE id = ?", id, id))
                    .isInstanceOf(Exception.class);
        }
    }

    // ------------------------------------------------------------------
    // B-012 · the reporting line, walked in MySQL
    // ------------------------------------------------------------------

    /**
     * B-012 against a real database, which is the only place the recursive CTE
     * is actually exercised — {@code ResourceWriteServiceTest} stubs the walk
     * and so proves what the service decides, not that MySQL agrees to do it.
     */
    @Nested
    @DisplayName("B-012 · reporting-manager cycles")
    class ManagerCycles {

        @Test
        @DisplayName("A→B→C→A is refused, three levels up")
        void aDeepCycleIsRefused() {
            long a = service.create(request("ITFRM040", "itfrm.chain.a", "PM")).resource().id();
            long b = reportingTo("ITFRM041", "itfrm.chain.b", a);
            long c = reportingTo("ITFRM042", "itfrm.chain.c", b);

            // C reports to B reports to A. Making C A's manager closes the loop
            // — the case the database CHECK has never caught.
            ResourceDtos.ResourceWriteRequest patch = request("ITFRM040", "itfrm.chain.a", "PM");
            patch.setReportingManagerId(Optional.of(c));

            assertThatThrownBy(() -> service.update(service.detail(a), patch))
                    .isInstanceOf(ResourceWriteService.ManagerCycleException.class);

            assertThat(service.detail(a).reportingManager()).isNull();
        }

        @Test
        @DisplayName("a chain of the same depth that does not lead back is saved")
        void aDeepChainThatTerminatesIsAccepted() {
            long a = service.create(request("ITFRM043", "itfrm.line.a", "PM")).resource().id();
            long b = reportingTo("ITFRM044", "itfrm.line.b", a);
            long c = reportingTo("ITFRM045", "itfrm.line.c", b);

            // Same shape as the refused case, minus the edge that closes it:
            // this is the test that would still pass if the guard simply
            // refused everything more than one level deep.
            long d = reportingTo("ITFRM046", "itfrm.line.d", c);

            assertThat(service.detail(d).reportingManager().id()).isEqualTo(c);
        }

        /**
         * The reason the walk is depth-bounded rather than run to a root.
         *
         * <p>The two rows below are a cycle the trigger permits, because neither
         * of them is self-reference — which is exactly what somebody repairing
         * data by hand can leave behind. An unbounded recursive CTE over them
         * runs to {@code cte_max_recursion_depth} and fails the statement, so an
         * admin editing a third, unrelated resource would get a 500 caused by
         * two rows they have never seen.
         */
        @Test
        @DisplayName("a cycle that already exists in the data stops the walk instead of the server")
        void aPreExistingCycleIsSurvivable() {
            long x = service.create(request("ITFRM047", "itfrm.loop.x", "PM")).resource().id();
            long y = service.create(request("ITFRM048", "itfrm.loop.y", "PM")).resource().id();
            jdbc.update("UPDATE users SET reporting_manager_id = ? WHERE id = ?", y, x);
            jdbc.update("UPDATE users SET reporting_manager_id = ? WHERE id = ?", x, y);

            long outsider = service.create(request("ITFRM049", "itfrm.loop.z", "DEVELOPER")).resource().id();
            ResourceDtos.ResourceWriteRequest patch = request("ITFRM049", "itfrm.loop.z", "DEVELOPER");
            patch.setReportingManagerId(Optional.of(x));

            // A refusal, not a 500 and not a hang. The outsider is not in the
            // cycle, so this is the depth cap firing and nothing else.
            assertThatThrownBy(() -> service.update(service.detail(outsider), patch))
                    .isInstanceOf(ResourceWriteService.ManagerCycleException.class);
        }

        @Test
        @DisplayName("a manager id that is not a resource is a validation error, not a foreign-key crash")
        void unknownManagerIsRefusedBeforeTheForeignKey() {
            long id = service.create(request("ITFRM050", "itfrm.nomgr", "DEVELOPER")).resource().id();
            ResourceDtos.ResourceWriteRequest patch = request("ITFRM050", "itfrm.nomgr", "DEVELOPER");
            patch.setReportingManagerId(Optional.of(Long.MAX_VALUE));

            assertThatThrownBy(() -> service.update(service.detail(id), patch))
                    .isInstanceOf(ResourceWriteService.ResourceValidationException.class);
        }

        private long reportingTo(String empCode, String username, long managerId) {
            ResourceDtos.ResourceWriteRequest request = request(empCode, username, "DEVELOPER");
            request.setReportingManagerId(Optional.of(managerId));
            return service.create(request).resource().id();
        }
    }

    // ------------------------------------------------------------------
    // the deactivation guard, against real tickets
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the form cannot deactivate somebody holding a real open ticket")
    void deactivationGuardSeesRealTickets() {
        long id = service.create(request("ITFRM030", "itfrm.busy", "DEVELOPER")).resource().id();
        insertOpenTicket(id);

        ResourceDtos.ResourceWriteRequest patch = request("ITFRM030", "itfrm.busy", "DEVELOPER");
        patch.setIsActive(Optional.of(false));

        assertThatThrownBy(() -> service.update(service.detail(id), patch))
                .isInstanceOf(ResourceWriteService.OpenTicketsException.class);

        assertThat(service.detail(id).isActive()).isTrue();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static ResourceDtos.ResourceWriteRequest request(String empCode, String username, String role) {
        ResourceDtos.ResourceWriteRequest request = new ResourceDtos.ResourceWriteRequest();
        request.setEmployeeCode(empCode);
        request.setUsername(username);
        request.setEmail(username + "@edunext.test");
        request.setDisplayName(username.replace('.', ' '));
        request.setRole(role);
        return request;
    }

    private long idOfProject(String code) {
        Long id = jdbc.queryForObject("SELECT id FROM projects WHERE project_code = ?", Long.class, code);
        return id == null ? 0L : id;
    }

    /**
     * "Open" is {@code statuses.is_open}, read from the seed rather than
     * hardcoded — the same rule {@code ResourceRepository} follows, for the same
     * reason: the status vocabulary is master data an Admin extends.
     */
    private void insertOpenTicket(long assigneeId) {
        Map<String, Object> status = jdbc.queryForMap(
                "SELECT code FROM statuses WHERE is_open = 1 ORDER BY id LIMIT 1");
        Long taskTypeId = jdbc.queryForObject("SELECT id FROM task_types ORDER BY id LIMIT 1", Long.class);

        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, task_type_id, title,
                                     level, original_level, status,
                                     assigned_to, reported_by, date_reported)
                VALUES (?, ?, ?, 'Form guard fixture', 'MEDIUM', 'MEDIUM', ?, ?, ?, UTC_TIMESTAMP(6))
                """,
                "ITFRM-26-00001", projectOneId, taskTypeId, status.get("code"), assigneeId, managerId);
    }
}
