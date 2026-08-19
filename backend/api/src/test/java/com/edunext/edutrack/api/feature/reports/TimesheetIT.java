package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-063 · the timesheet against real MySQL.
 *
 * <p>Three things only a database can answer, and the unit test cannot:
 *
 * <ul>
 *   <li><b>the visibility rule</b>, which is rows — who reports to whom and who
 *       shares a project — and is the part of this feature that discloses staff
 *       rather than tickets if it is wrong;</li>
 *   <li><b>the query</b>: that a week's entries are found, joined to the names
 *       that make them readable, and bounded at both ends;</li>
 *   <li><b>capacity</b>, which comes from the seeded working calendar — 09:30 to
 *       18:30, Saturday and Sunday off — rather than from a number in a test.</li>
 * </ul>
 *
 * <p>Isolation is by identity, as everywhere in this feature: nothing is
 * deleted, because A-008's triggers refuse it on the append-only tables, so
 * each test seeds its own people, tickets and hours.
 */
@SpringBootTest
@Testcontainers
class TimesheetIT {

    /*
      UTC before Testcontainers starts, and that ordering is the whole point.

      EduTrackApplication has a static initialiser setting the default zone to
      UTC — its own comment explains why: a LocalDate read back through a driver
      whose default zone is east of Greenwich comes back a day early. In a
      @SpringBootTest that block runs when Spring loads the application class,
      which is *after* this @Container field has already initialised the MySQL
      driver. The driver captures the zone it was loaded under, Spring then
      changes it, and the two disagree by the offset — 09:30 on the 12th is read
      back as the 11th, and a test asserting that Wednesday is a holiday quietly
      proves it about Tuesday.

      A static block here runs before the @Container field below it, in textual
      order, so the driver and the application agree from the start. CI runs on
      UTC hosts where the mismatch cannot arise; this is what makes the same
      test mean the same thing on a laptop that does not.

      WorkingHoursServiceIT does not need this: it is a @DataJpaTest, so nothing
      ever changes the zone underneath it.
    */
    static {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    }

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_timesheet_it")
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
    TimesheetService service;

    @Autowired
    JdbcTemplate jdbc;

    /** Monday 10 August 2026. The Saturday and Sunday are the 15th and 16th. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 10);

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final CallerIdentity ADMIN = new CallerIdentity(1L, "ADMIN", List.of());

    private long projectA;
    private long projectB;
    private long subject;
    private long manager;
    private long teammate;
    private long outsider;
    private long taskType;
    private long ticket;

    @BeforeEach
    void seed() {
        projectA = project("TSA");
        projectB = project("TSB");
        subject = user("ts.subject", "DEVELOPER");
        manager = user("ts.manager", "PM");
        teammate = user("ts.mate", "PM");
        outsider = user("ts.outsider", "PM");
        taskType = taskType("Bug");

        jdbc.update("UPDATE users SET reporting_manager_id = ? WHERE id = ?", manager, subject);

        member(subject, projectA);
        member(teammate, projectA);
        member(manager, projectB);
        member(outsider, projectB);

        ticket = ticket("TS");
    }

    /**
     * The one thing here that is not isolated by identity.
     *
     * <p>A holiday is org-wide by definition, so a test seeding one changes
     * every other test's capacity — the reason this class cleans up after
     * itself where {@code Profile360IT} never needs to. {@code holidays} is an
     * ordinary master table, not one of the append-only three, so a delete is
     * available here and would be refused there.
     */
    @AfterEach
    void clearHolidays() {
        jdbc.update("DELETE FROM holidays WHERE name LIKE 'ts-%'");
    }

    @Nested
    @DisplayName("who may open whose week")
    class Visibility {

        @Test
        @DisplayName("an Admin opens anybody's")
        void admin() {
            assertThat(service.week(ADMIN, subject, MONDAY)).isPresent();
        }

        @Test
        @DisplayName("you always open your own, whatever your role")
        void self() {
            // A Developer is entitled to their own week even though §2 denies
            // them everybody else's — the same "Own perf." grant the profile
            // rests on. A timesheet nobody could open for themselves would be
            // a strange thing to ask people to fill in.
            assertThat(service.week(caller(subject, "DEVELOPER"), subject, MONDAY)).isPresent();
        }

        @Test
        @DisplayName("a PM opens a direct reportee's, even on another project")
        void reporteeAcrossProjects() {
            assertThat(service.week(caller(manager, "PM"), subject, MONDAY)).isPresent();
        }

        @Test
        @DisplayName("a PM opens the week of somebody on a project they share")
        void projectMate() {
            assertThat(service.week(caller(teammate, "PM"), subject, MONDAY)).isPresent();
        }

        @Test
        @DisplayName("a PM sharing neither a project nor a reporting line gets nothing")
        void outsiderRefused() {
            assertThat(service.week(caller(outsider, "PM"), subject, MONDAY)).isEmpty();
        }

        @ParameterizedTest(name = "{0} cannot open a colleague''s week")
        @ValueSource(strings = {"DEVELOPER", "QA", "DEPLOYMENT"})
        @DisplayName("the three delivery roles open nobody's but their own")
        void deliveryRolesRefused(String role) {
            assertThat(service.week(caller(teammate, role), subject, MONDAY)).isEmpty();
        }

        @Test
        @DisplayName("a refusal and a missing user are the same answer, so ids cannot be enumerated")
        void refusalLooksLikeAbsence() {
            assertThat(service.week(caller(outsider, "PM"), subject, MONDAY)).isEmpty();
            assertThat(service.week(caller(outsider, "PM"), 9_999_999L, MONDAY)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the week that comes back")
    class TheWeek {

        @Test
        @DisplayName("finds the hours, and names the ticket, project and stage they were logged against")
        void readsTheLog() {
            effort("DEV", 1, 1, MONDAY, "3.50");

            TimesheetDtos.Row row = week().rows().getFirst();

            // The code, not the numeric id — a ticket has one name in this API.
            assertThat(row.ticketId()).startsWith("TS");
            assertThat(row.project().id()).isEqualTo(projectA);
            assertThat(row.stage()).isEqualTo("DEV");
            assertThat(row.totalHours()).isEqualTo(new BigDecimal("3.50"));
        }

        @Test
        @DisplayName("splits one ticket by the stage and iteration the hours were stamped with")
        void stageAware() {
            // The task in one assertion. Both entries are the same ticket and
            // the same person; only the ribbon position differs, and a week that
            // could not say so would report five undifferentiated hours.
            effort("DEV", 1, 1, MONDAY, "2.00");
            effort("QA", 2, 1, MONDAY.plusDays(1), "3.00");

            assertThat(week().rows()).hasSize(2);
            assertThat(week().rows()).extracting(TimesheetDtos.Row::stage)
                    .containsExactly("QA", "DEV");   // busiest first
        }

        @Test
        @DisplayName("stops at both ends of the week")
        void bounded() {
            effort("DEV", 1, 1, MONDAY.minusDays(1), "8.00");    // the Sunday before
            effort("DEV", 1, 1, MONDAY, "1.00");
            effort("DEV", 1, 1, MONDAY.plusDays(6), "2.00");     // this week's Sunday
            effort("DEV", 1, 1, MONDAY.plusDays(7), "8.00");     // the Monday after

            // Inclusive at both ends — work_date is a DATE, so neither boundary
            // needs the exclusive-upper dance a DATETIME comparison would.
            assertThat(week().totalHours()).isEqualTo(new BigDecimal("3.00"));
        }

        @Test
        @DisplayName("a reversal nets out rather than disappearing")
        void correction() {
            long entry = effort("DEV", 1, 1, MONDAY, "5.00");
            reversal(entry, "DEV", 1, 1, MONDAY, "-5.00");
            effort("DEV", 1, 1, MONDAY, "2.00");

            TimesheetDtos.Timesheet sheet = week();

            assertThat(sheet.totalHours()).isEqualTo(new BigDecimal("2.00"));
            assertThat(sheet.rows().getFirst().hasCorrection()).isTrue();
        }

        @Test
        @DisplayName("an empty week is seven days and no rows, not an error")
        void emptyWeek() {
            TimesheetDtos.Timesheet sheet = week();

            assertThat(sheet.rows()).isEmpty();
            assertThat(sheet.days()).hasSize(7);
            assertThat(sheet.totalHours()).isEqualTo(new BigDecimal("0.00"));
        }
    }

    @Nested
    @DisplayName("capacity, from the calendar rather than from arithmetic")
    class Capacity {

        @Test
        @DisplayName("a weekday offers the seeded calendar's nine hours and a weekend offers none")
        void weekdayAndWeekend() {
            // V20260808_1630 seeds 09:30–18:30 with Saturday and Sunday off. If
            // this ever reads 8, somebody has computed a working day rather than
            // asked the calendar for one.
            TimesheetDtos.Timesheet sheet = week();

            assertThat(sheet.days().get(0).capacityHours()).isEqualTo(new BigDecimal("9.00"));
            assertThat(sheet.days().get(0).isWorkingDay()).isTrue();
            assertThat(sheet.days().get(5).capacityHours()).isEqualTo(new BigDecimal("0.00"));
            assertThat(sheet.days().get(5).isWorkingDay()).isFalse();
            assertThat(sheet.capacityHours()).isEqualTo(new BigDecimal("45.00"));
        }

        @Test
        @DisplayName("an org holiday removes that day's capacity")
        void orgHoliday() {
            holiday(MONDAY.plusDays(2), "ts-org-holiday");

            TimesheetDtos.Timesheet sheet = week();

            assertThat(sheet.days().get(2).capacityHours()).isEqualTo(new BigDecimal("0.00"));
            assertThat(sheet.days().get(2).isWorkingDay()).isFalse();
            assertThat(sheet.capacityHours()).isEqualTo(new BigDecimal("36.00"));
        }

        @Test
        @DisplayName("the resource's own approved leave removes theirs, and nobody else's")
        void resourceLeave() {
            leave(subject, MONDAY.plusDays(1), MONDAY.plusDays(1));

            assertThat(week().days().get(1).capacityHours()).isEqualTo(new BigDecimal("0.00"));
            // The teammate's Tuesday is untouched — leave is per resource, which
            // is why the userId is passed to the calendar at all.
            assertThat(service.week(ADMIN, teammate, MONDAY).orElseThrow()
                    .days().get(1).capacityHours()).isEqualTo(new BigDecimal("9.00"));
        }

        @Test
        @DisplayName("utilisation is measured against what the calendar actually offered")
        void utilisation() {
            holiday(MONDAY.plusDays(2), "ts-day-off");
            effort("DEV", 1, 1, MONDAY, "9.00");

            // 9 logged against 36 available, not against 45 — a holiday nobody
            // was expected to work must not depress the figure.
            assertThat(week().utilisationPct()).isEqualTo(new BigDecimal("25.0"));
        }
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    /*
      Every date below is inserted as an ISO string rather than as a LocalDate,
      which is not a style choice. A LocalDate handed to JdbcTemplate is
      converted through the JVM's zone on the way to a container running UTC, so
      on a machine east of Greenwich a holiday written for Wednesday is stored
      against Tuesday — and the test then proves the wrong day. WorkingHoursServiceIT
      inserts strings for the same reason. Production is unaffected: Hibernate
      writes these columns, and it does not convert a date.
    */

    private TimesheetDtos.Timesheet week() {
        return service.week(ADMIN, subject, MONDAY).orElseThrow();
    }

    private CallerIdentity caller(long id, String role) {
        return new CallerIdentity(id, role, List.of(projectA));
    }

    private long project(String code) {
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, ?, 'ACTIVE')",
                code + SEQ.incrementAndGet(), "Timesheet IT");
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long user(String name, String role) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", Long.class, role);
        String u = name + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id, is_active)
                VALUES (?, ?, ?, 'x', ?, ?, 1)
                """, u, u, u + "@example.test", u, roleId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void member(long userId, long projectId) {
        jdbc.update("""
                INSERT INTO project_members (project_id, user_id, role_in_project)
                VALUES (?, ?, 'DEVELOPER')
                """, projectId, userId);
    }

    private long taskType(String name) {
        jdbc.update("INSERT INTO task_types (code, name, is_active) VALUES (?, ?, 1)",
                name.toUpperCase() + SEQ.incrementAndGet(), name);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long ticket(String label) {
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, task_type_id, level, original_level,
                                     status, date_reported, reported_by, assigned_to, current_stage,
                                     estimated_effort_hrs, total_effort_hrs, current_cycle_no)
                VALUES (?, ?, 'Timesheet IT', ?, 'MEDIUM', 'MEDIUM', 'IN_PROGRESS',
                        '2026-08-10 09:00:00', ?, ?, 'DEV', 8, 0, 1)
                """, label + "-" + SEQ.incrementAndGet(), projectA, taskType, subject, subject);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /**
     * A hand-written effort row, hashes included as literals.
     *
     * <p>The same shape {@code ReportRunnersIT} uses: the chain is A-040's to
     * compute and verify, and a report reading the table has no opinion about
     * it — seeding through {@code TicketJournal} would be testing the journal.
     */
    private long effort(String stage, int iteration, int cycle, LocalDate date, String hours) {
        jdbc.update("""
                INSERT INTO ticket_effort_logs (ticket_id, cycle_no, stage_code, iteration_no,
                                                user_id, work_date, hours, prev_hash, row_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'x', ?)
                """, ticket, cycle, stage, iteration, subject, date.toString(), new BigDecimal(hours),
                "h" + SEQ.incrementAndGet());
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** The negative compensating row {@code ck_effort_hours} permits only for a correction. */
    private void reversal(long correctsEntryId, String stage, int iteration, int cycle,
                          LocalDate date, String hours) {
        jdbc.update("""
                INSERT INTO ticket_effort_logs (ticket_id, cycle_no, stage_code, iteration_no,
                                                user_id, work_date, hours, is_correction,
                                                corrects_entry_id, prev_hash, row_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, 'x', ?)
                """, ticket, cycle, stage, iteration, subject, date.toString(), new BigDecimal(hours),
                correctsEntryId, "h" + SEQ.incrementAndGet());
    }

    private void holiday(LocalDate date, String name) {
        jdbc.update("""
                INSERT INTO holidays (holiday_date, name, is_recurring)
                VALUES (?, ?, 0)
                """, date.toString(), name + SEQ.incrementAndGet());
    }

    private void leave(long userId, LocalDate from, LocalDate to) {
        jdbc.update("""
                INSERT INTO resource_leaves (user_id, start_date, end_date, is_half_day, status)
                VALUES (?, ?, ?, 0, 'APPROVED')
                """, userId, from.toString(), to.toString());
    }
}
