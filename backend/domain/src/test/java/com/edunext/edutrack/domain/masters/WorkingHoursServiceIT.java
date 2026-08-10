package com.edunext.edutrack.domain.masters;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-024 · the working-hours service against real MySQL and B-023's seeded
 * calendar, not fixture mocks.
 *
 * <p>{@link WorkingHoursServiceTest} proves the day-walk algorithm cheaply, on
 * every build. This proves the two pieces a unit test cannot: that
 * {@link HolidayRepository#findAllOrgWideOrForProject} really does return a
 * recurring holiday stored in a year outside the query window (the whole
 * reason that method exists rather than reusing the date-windowed one), and
 * that the seeded default calendar — Sat/Sun, 09:30–18:30, Asia/Kolkata —
 * produces the answer blueprint §5 names explicitly: a Friday-evening ticket
 * with a four-hour SLA lands Monday morning, not Saturday.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WorkingHoursServiceIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_working_hours_it")
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
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.jdbc.time_zone", () -> "UTC");
    }

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    @Autowired
    DataSource dataSource;

    @Autowired
    WorkingCalendarRepository calendars;

    @Autowired
    HolidayRepository holidays;

    @Autowired
    ResourceLeaveRepository leaves;

    JdbcClient db;
    WorkingHoursService service;

    /** {@code resource_leaves} has a real FK to {@code users}; inserted per test rather than assumed. */
    Long userId;

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(dataSource);
        service = new WorkingHoursService(calendars, holidays, leaves);
        userId = insertUser();
    }

    private Long insertUser() {
        Integer roleId = db.sql("SELECT id FROM roles WHERE code = 'DEVELOPER'").query(Integer.class).single();
        db.sql("INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id) "
                        + "VALUES ('E-IT-1', 'it.developer', 'it.developer@example.com', 'x', 'IT Developer', ?)")
                .param(roleId)
                .update();
        return db.sql("SELECT id FROM users WHERE username = 'it.developer'").query(Long.class).single();
    }

    private static Instant kolkata(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, KOLKATA).toInstant();
    }

    // ------------------------------------------------------------------
    // The seeded default calendar, no holidays or leave
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("against the seeded default — Sat/Sun, 09:30-18:30, Asia/Kolkata")
    class SeededDefault {

        @Test
        @DisplayName("a window inside one working day is its plain wall-clock length")
        void withinOneDay() {
            BigDecimal hours = service.workingHoursBetween(
                    kolkata(2026, 8, 12, 10, 0), kolkata(2026, 8, 12, 14, 30));

            assertThat(hours).isEqualByComparingTo("4.50");
        }

        /**
         * The exact scenario blueprint §5 and CLAUDE.md both name: a Friday
         * evening ticket must not breach over the weekend.
         */
        @Test
        @DisplayName("Friday 18:00 plus a 4-hour SLA lands Monday morning, not Saturday")
        void fridayEveningPlusFourHours() {
            Instant landed = service.addWorkingHours(kolkata(2026, 8, 14, 18, 0), new BigDecimal("4"));

            assertThat(landed).isEqualTo(kolkata(2026, 8, 17, 13, 0));
        }

        @Test
        @DisplayName("workingHoursBetween across the same weekend excludes Saturday and Sunday entirely")
        void spanningAWeekendExcludesIt() {
            BigDecimal hours = service.workingHoursBetween(
                    kolkata(2026, 8, 14, 17, 0), kolkata(2026, 8, 17, 10, 30));

            assertThat(hours).as("1.5h left on Friday, 1h into Monday").isEqualByComparingTo("2.50");
        }
    }

    // ------------------------------------------------------------------
    // Holidays — org-wide and recurring
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("holidays")
    class Holidays {

        @Test
        @DisplayName("a non-recurring org-wide holiday is skipped like a weekend")
        void nonRecurringHolidayIsSkipped() {
            insertHoliday("2026-08-19", "Ad-hoc closure", false);

            BigDecimal hours = service.workingHoursBetween(
                    kolkata(2026, 8, 18, 9, 30), kolkata(2026, 8, 20, 18, 30));

            assertThat(hours).as("18th and 20th are full days; the 19th contributes nothing")
                    .isEqualByComparingTo("18.00");
        }

        /**
         * The whole reason {@link HolidayRepository#findAllOrgWideOrForProject}
         * exists rather than reusing {@code findOrgWideOrForProject}: a
         * recurring holiday stored in 2020 must still be honoured in 2026,
         * which a query filtering on the stored {@code holiday_date} cannot see.
         */
        @Test
        @DisplayName("a recurring holiday stored in a past year is still honoured this year")
        void recurringHolidayExpandsIntoTheCurrentYear() {
            // Same month-day as the non-recurring case above, stored against
            // 2020 — 2026-08-18/20 are already confirmed weekdays either side.
            insertHoliday("2020-08-19", "Recurring closure", true);

            BigDecimal hours = service.workingHoursBetween(
                    kolkata(2026, 8, 18, 9, 30), kolkata(2026, 8, 20, 18, 30));

            assertThat(hours).as("the 18th and 20th are full days; the 19th recurs and is skipped")
                    .isEqualByComparingTo("18.00");
        }

        private void insertHoliday(String date, String name, boolean recurring) {
            db.sql("INSERT INTO holidays (holiday_date, name, is_recurring) VALUES (?, ?, ?)")
                    .param(date).param(name).param(recurring)
                    .update();
        }
    }

    // ------------------------------------------------------------------
    // Resource leave
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("resource leave")
    class Leave {

        @Test
        @DisplayName("a full-day approved leave removes that resource's whole day")
        void fullDayLeaveRemovesTheDay() {
            insertLeave(userId, "2026-08-19", "2026-08-19", false, "APPROVED");

            BigDecimal hours = service.workingHoursBetween(
                    kolkata(2026, 8, 18, 9, 30), kolkata(2026, 8, 20, 18, 30), null, userId);

            assertThat(hours).isEqualByComparingTo("18.00");
        }

        @Test
        @DisplayName("a pending (non-approved) leave does not stop the clock")
        void nonApprovedLeaveIsIgnored() {
            insertLeave(userId, "2026-08-19", "2026-08-19", false, "PENDING");

            BigDecimal hours = service.workingHoursBetween(
                    kolkata(2026, 8, 18, 9, 30), kolkata(2026, 8, 20, 18, 30), null, userId);

            assertThat(hours).as("only approved leave is honoured").isEqualByComparingTo("27.00");
        }

        @Test
        @DisplayName("a half-day approved leave halves that day's capacity")
        void halfDayLeaveHalvesCapacity() {
            insertLeave(userId, "2026-08-19", "2026-08-19", true, "APPROVED");

            BigDecimal hours = service.workingHoursBetween(
                    kolkata(2026, 8, 19, 9, 30), kolkata(2026, 8, 19, 18, 30), null, userId);

            assertThat(hours).isEqualByComparingTo("4.50");
        }

        private void insertLeave(Long userId, String start, String end, boolean halfDay, String status) {
            db.sql("INSERT INTO resource_leaves (user_id, start_date, end_date, is_half_day, status) "
                            + "VALUES (?, ?, ?, ?, ?)")
                    .param(userId).param(start).param(end).param(halfDay).param(status)
                    .update();
        }
    }
}
