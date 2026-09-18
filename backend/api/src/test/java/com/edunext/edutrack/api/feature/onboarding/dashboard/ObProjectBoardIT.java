package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObProjectBoard;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObProjectBoardRow;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
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

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The project board against real MySQL and the real working calendar.
 *
 * <h2>What this proves that {@code ObProjectBoardServiceTest} cannot</h2>
 *
 * <ol>
 *   <li><b>The completion date is the Projects grid's own.</b> The board reads
 *       it through {@code ObRunningProjectReader}, which runs that feature's
 *       real query and its real TAT walk — so a project seeded here lands in a
 *       bucket computed from the same date the grid would print, and nothing
 *       in this file recomputes it.</li>
 *   <li><b>The Friday-to-Monday example, against {@link WorkingHoursService}
 *       itself</b> rather than a fake that counts weekdays — CLAUDE.md names
 *       this case, and the unit test can only assert it against its own
 *       arithmetic.</li>
 *   <li><b>Row scope.</b> The predicate is applied inside the projects read,
 *       and the only way to know it survived the trip is to run it.</li>
 *   <li><b>The escalation and task counts join what they claim to join.</b></li>
 * </ol>
 */
@SpringBootTest
@Testcontainers
class ObProjectBoardIT {

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

    /** A Wednesday, an hour into the working day. */
    private static final Instant WEDNESDAY = Instant.parse("2026-09-16T10:00:00Z");

    @Autowired
    ObProjectBoardRepository counts;

    @Autowired
    com.edunext.edutrack.api.feature.onboarding.projects.ObRunningProjectReader projects;

    @Autowired
    WorkingHoursService workingHours;

    @Autowired
    WorkingCalendarRepository calendars;

    @Autowired
    JdbcTemplate jdbc;

    private long priya;
    private long aarav;
    private long erp;
    private long biometric;
    private long erpTemplate;

    @BeforeEach
    void seed() {
        jdbc.update("UPDATE working_calendar SET timezone = 'UTC' WHERE id = 1");

        /*
          Order matters and follows the foreign keys down: escalations before
          steps, steps before journeys, journeys before projects. A new FK table
          pointing at any of these has to join this list or every case here
          errors in seed rather than failing on an assertion — the lesson
          `ObClientsIT` records.
        */
        jdbc.update("DELETE FROM ob_client_escalations");
        jdbc.update("DELETE FROM ob_journey_steps");
        jdbc.update("DELETE FROM ob_journeys");
        jdbc.update("DELETE FROM ob_projects");
        jdbc.update("DELETE FROM ob_journey_templates WHERE name LIKE 'it_obboard_%'");
        jdbc.update("DELETE FROM ob_client_applications WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'it_obboard_%')");
        jdbc.update("DELETE FROM ob_clients WHERE name LIKE 'it_obboard_%'");
        jdbc.update("DELETE FROM ob_products WHERE code LIKE 'IT_OBBOARD_%'");
        jdbc.update("DELETE FROM users WHERE username LIKE 'it_obboard_%'");

        priya = insertUser("it_obboard_priya");
        aarav = insertUser("it_obboard_aarav");
        erp = insertProduct("IT_OBBOARD_ERP", "ERP");
        biometric = insertProduct("IT_OBBOARD_BIO", "Biometric");
        erpTemplate = insertTemplate(erp);
    }

    // ── the calendar, for real ──────────────────────────────────────────────

    @Test
    @DisplayName("a project whose completion date fell on Friday is on time on Saturday and delayed on Monday")
    void theFridayToMondayExampleAgainstTheRealCalendar() {
        /*
          Six working days of budget from the opening of Friday 4 September:
          Friday itself, then Mon–Fri, which the calendar walk lands on the
          close of Friday the 11th. The date is asserted rather than assumed,
          because the whole case rests on the completion date being a Friday —
          a budget that landed on Thursday would make the Saturday reading
          "one day late" and the example would prove nothing.
        */
        long client = insertClient("it_obboard_horizon", priya);
        long project = insertProject(client, "2026-09-04", priya, aarav);
        long journey = insertJourney(project, client);
        insertStep(journey, 1, "Kick-off", "IN_PROGRESS", aarav, 6, null);

        ObProjectBoardRow saturday = only(board(Instant.parse("2026-09-12T10:00:00Z")));
        assertThat(saturday.tentativeCompletion()).isEqualTo(LocalDate.parse("2026-09-11"));
        assertThat(saturday.daysPastCompletion()).isNull();
        assertThat(saturday.bucket()).isEqualTo("ON_TIME");

        ObProjectBoardRow monday = only(board(Instant.parse("2026-09-14T10:00:00Z")));
        // A naive Instant.until(ChronoUnit.DAYS) would say 3 — Sat, Sun, Mon.
        assertThat(monday.daysPastCompletion()).isEqualTo(1);
        assertThat(monday.bucket()).isEqualTo("DELAYED");
    }

    @Test
    @DisplayName("a project with no task has no completion date and is NOT_SCHEDULED")
    void aProjectWithNoTaskIsNotScheduled() {
        long client = insertClient("it_obboard_unstarted", priya);
        insertProject(client, "2026-09-01", priya, aarav);

        ObProjectBoardRow row = only(board(WEDNESDAY));

        assertThat(row.tentativeCompletion()).isNull();
        assertThat(row.bucket()).isEqualTo("NOT_SCHEDULED");
        assertThat(row.tasksTotal()).isZero();
    }

    // ── the counts this repository owns ─────────────────────────────────────

    @Test
    @DisplayName("task progress folds every module service of the project, and SKIPPED counts as settled")
    void taskProgressFoldsTheProjectAndCountsSkippedAsSettled() {
        long client = insertClient("it_obboard_progress", priya);
        long project = insertProject(client, "2026-09-01", priya, aarav);
        long first = insertJourney(project, client);
        long second = insertJourney(project, client);
        insertStep(first, 1, "Done", "DONE", aarav, 2, null);
        insertStep(first, 2, "Waived", "SKIPPED", aarav, 2, null);
        insertStep(second, 1, "Still running", "IN_PROGRESS", aarav, 2, null);

        ObProjectBoardRow row = only(board(WEDNESDAY));

        assertThat(row.tasksTotal()).isEqualTo(3);
        assertThat(row.tasksDone()).isEqualTo(2);
    }

    @Test
    @DisplayName("the escalations card counts projects with an open escalation, and ignores resolved ones")
    void openEscalationsOnly() {
        long client = insertClient("it_obboard_escalated", priya);
        long project = insertProject(client, "2026-09-01", priya, aarav);
        long journey = insertJourney(project, client);
        long step = insertStep(journey, 1, "Configuration", "IN_PROGRESS", aarav, 3, null);
        insertEscalation(client, journey, step, null);

        long quiet = insertClient("it_obboard_quiet", priya);
        long quietProject = insertProject(quiet, "2026-09-01", priya, aarav);
        long quietJourney = insertJourney(quietProject, quiet);
        long quietStep = insertStep(quietJourney, 1, "Configuration", "IN_PROGRESS", aarav, 3, null);
        insertEscalation(quiet, quietJourney, quietStep, WEDNESDAY);

        ObProjectBoard board = board(WEDNESDAY);

        assertThat(board.cards().ongoingProjects()).isEqualTo(2);
        assertThat(board.cards().clientEscalations()).isEqualTo(1);
        assertThat(rowFor(board, project).openEscalations()).isEqualTo(1);
        assertThat(rowFor(board, quietProject).openEscalations()).isZero();
    }

    // ── scope, and the statuses that are not on the board ───────────────────

    @Test
    @DisplayName("only RUNNING projects are on the board — a completed or held one is not")
    void onlyRunningProjectsAreCounted() {
        long client = insertClient("it_obboard_mixed", priya);
        insertProject(client, "2026-09-01", priya, aarav);
        long held = insertProject(client, biometric, "2026-09-01", priya, aarav);
        jdbc.update("UPDATE ob_projects SET status = 'ON_HOLD', status_reason = 'Client paused' WHERE id = ?", held);

        assertThat(board(WEDNESDAY).cards().ongoingProjects()).isEqualTo(1);
    }

    @Test
    @DisplayName("OB_SALES sees only the projects of clients they created")
    void salesSeesOnlyTheirOwnClients() {
        long mine = insertClient("it_obboard_mine", priya);
        insertProject(mine, "2026-09-01", priya, aarav);

        assertThat(board(WEDNESDAY, caller(priya, "OB_SALES")).cards().ongoingProjects()).isEqualTo(1);
        assertThat(board(WEDNESDAY, caller(aarav, "OB_SALES")).cards().ongoingProjects()).isZero();
    }

    @Test
    @DisplayName("a caller with no recognised module role gets an empty board rather than an error")
    void anUnrecognisedRoleSeesAnEmptyBoard() {
        long client = insertClient("it_obboard_hidden", priya);
        insertProject(client, "2026-09-01", priya, aarav);

        ObProjectBoard board = board(WEDNESDAY, caller(99, "TICKETING_MEMBER"));

        assertThat(board.projects()).isEmpty();
        assertThat(board.cards().ongoingProjects()).isZero();
        assertThat(board.appliedScope()).isEqualTo("nothing");
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private ObProjectBoard board(Instant now) {
        return board(now, caller(priya, "OB_ADMIN"));
    }

    private ObProjectBoard board(Instant now, CallerIdentity caller) {
        return new ObProjectBoardService(projects, counts, workingHours, calendars,
                Clock.fixed(now, ZoneOffset.UTC))
                .board(caller);
    }

    private static ObProjectBoardRow only(ObProjectBoard board) {
        assertThat(board.projects()).hasSize(1);
        return board.projects().get(0);
    }

    private static ObProjectBoardRow rowFor(ObProjectBoard board, long projectId) {
        return board.projects().stream().filter(row -> row.id() == projectId).findFirst().orElseThrow();
    }

    private static CallerIdentity caller(long userId, String moduleRole) {
        return new CallerIdentity(userId, "SUPPORT", List.of(), List.of("ONBOARDING"),
                Map.of("ONBOARDING", moduleRole));
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

    private long insertTemplate(long productId) {
        jdbc.update("""
                INSERT INTO ob_journey_templates (product_id, name, version, is_active)
                VALUES (?, ?, 1, 1)
                """, productId, "it_obboard_template_" + productId + "_" + System.nanoTime());
        return lastId();
    }

    private long insertClient(String name, long createdBy) {
        jdbc.update("""
                INSERT INTO ob_clients (name, onboarding_date, overall_status, created_by)
                VALUES (?, '2026-03-01', 'ONBOARDING', ?)
                """, name, createdBy);
        jdbc.update("INSERT INTO ob_client_applications (ob_client_id, product_id) VALUES (?, ?)",
                lastIdOf("ob_clients", name), erp);
        return lastIdOf("ob_clients", name);
    }

    private long insertProject(long clientId, String startDate, long salesPersonId, long implementorId) {
        return insertProject(clientId, erp, startDate, salesPersonId, implementorId);
    }

    /**
     * One project per (client, product) — {@code uq_ob_projects_client_product}
     * has held since the module's first migration, so a case wanting a second
     * project for one client has to give it a second product.
     */
    private long insertProject(long clientId, long productId, String startDate,
                               long salesPersonId, long implementorId) {
        jdbc.update("""
                INSERT INTO ob_projects (ob_client_id, product_id, name, start_date,
                                         sales_person_id, implementor_user_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, clientId, productId, "it_obboard_project_" + System.nanoTime(), startDate,
                salesPersonId, implementorId, salesPersonId);
        return lastId();
    }

    /** An open, running journey — the board never counts a locked or archived one. */
    private long insertJourney(long projectId, long clientId) {
        // `service_name` is NOT NULL and carries no default: a journey is one
        // module service of the project, and the column is what names it.
        jdbc.update("""
                INSERT INTO ob_journeys (project_id, ob_client_id, product_id, template_id,
                                         service_name, gate_status, gate_opened_at, started_at)
                VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?)
                """, projectId, clientId, erp, erpTemplate, "it_obboard_service_" + System.nanoTime(),
                Timestamp.from(Instant.parse("2026-09-01T09:00:00Z")),
                Timestamp.from(Instant.parse("2026-09-01T09:00:00Z")));
        return lastId();
    }

    private long insertStep(long journeyId, int sequence, String name, String status, Long owner,
                            int tatDays, Instant dueAt) {
        /*
          Two checks bind a reason to a status in the same row —
          ck_ob_journey_steps_blocked_reason and ck_ob_journey_steps_skip_reason
          — so both reasons travel in this INSERT. A follow-up UPDATE would
          violate the check on the first statement.
        */
        String blockedReason = "BLOCKED".equals(status) ? "OTHER" : null;
        String skipReason = "SKIPPED".equals(status) ? "Not applicable to this client" : null;
        jdbc.update("""
                INSERT INTO ob_journey_steps (journey_id, sequence, name, tat_days, status,
                                              owner_user_id, due_at, blocked_reason_code, skip_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, journeyId, sequence, name, tatDays, status, owner,
                dueAt == null ? null : Timestamp.from(dueAt), blockedReason, skipReason);
        return lastId();
    }

    private void insertEscalation(long clientId, long journeyId, long stepId, Instant resolvedAt) {
        // An escalation is raised by a client *contact*, not a user —
        // `fk_ob_client_escalations_contact` — so the portal principal has to
        // exist before the escalation can point at it.
        jdbc.update("""
                INSERT INTO ob_client_contacts (ob_client_id, name, email, is_primary)
                VALUES (?, 'it_obboard_contact', ?, 1)
                """, clientId, "contact" + System.nanoTime() + "@example.com");
        long contactId = lastId();
        jdbc.update("""
                INSERT INTO ob_client_escalations (ob_client_id, journey_id, step_id,
                                                   raised_by_contact_id, comment, raised_at,
                                                   resolved_at, resolved_by)
                VALUES (?, ?, ?, ?, 'No progress for a fortnight.', ?, ?, ?)
                """, clientId, journeyId, stepId, contactId,
                Timestamp.from(Instant.parse("2026-09-10T09:00:00Z")),
                resolvedAt == null ? null : Timestamp.from(resolvedAt),
                resolvedAt == null ? null : priya);
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** {@code LAST_INSERT_ID()} is per connection and the application insert above moved it. */
    private long lastIdOf(String table, String name) {
        return jdbc.queryForObject("SELECT id FROM " + table + " WHERE name = ?", Long.class, name);
    }
}
