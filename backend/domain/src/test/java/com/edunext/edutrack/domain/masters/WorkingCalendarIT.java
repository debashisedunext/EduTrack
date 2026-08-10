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

import jakarta.persistence.EntityManager;

import javax.sql.DataSource;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-023 · {@code working_calendar} against real MySQL 8.4.
 *
 * <p>Two things here can only be proven against a real server. The first is
 * {@code JSON_SCHEMA_VALID} inside a {@code CHECK} constraint — MySQL rejects
 * non-deterministic expressions in checks, and this is the first constraint in
 * the schema to use it, so "it parses" is not evidence it is being enforced. The
 * second is that {@code ck_working_calendar_weekly_off} actually refuses the
 * specific value the API contract used to advertise: a {@code 0} meaning Sunday.
 * A unit test cannot fail that way.
 *
 * <p>Container flags mirror {@code docker-compose.yml}, as in {@code SeedDataIT}
 * — a database migrated exactly the way a real one is.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WorkingCalendarIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_calendar_it")
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

    @Autowired
    DataSource dataSource;

    @Autowired
    WorkingCalendarRepository repository;

    @Autowired
    EntityManager em;

    JdbcClient db;

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(dataSource);
    }

    // ------------------------------------------------------------------
    // The seeded row
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the seeded default")
    class Seeded {

        @Test
        @DisplayName("is a Sat/Sun weekend, 09:30–18:30, Asia/Kolkata")
        void matchesWhatTheMockHasAlwaysServed() {
            WorkingCalendar calendar = repository.getCalendar();

            assertThat(calendar.getWeeklyOff())
                    .as("[6, 7] is Saturday and Sunday under ISO-8601 — the values the "
                            + "contract now specifies")
                    .containsExactlyInAnyOrder(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
            assertThat(calendar.getWorkDayStart()).isEqualTo(LocalTime.of(9, 30));
            assertThat(calendar.getWorkDayEnd()).isEqualTo(LocalTime.of(18, 30));
            assertThat(calendar.zone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
            assertThat(calendar.workDayLength()).isEqualTo(Duration.ofHours(9));
        }

        /**
         * The regression test for a defect in this table's first draft.
         *
         * <p>The boundaries were {@code TIME}/{@code LocalTime}. Both {@code api}
         * and {@code worker} set {@code hibernate.jdbc.time_zone: UTC}, so the
         * value round-tripped through a UTC calendar and came back shifted by the
         * JVM zone's offset — 09:30 written, 15:00 read on an IST machine. Every
         * working-hours figure would have been wrong by that offset, and wrong
         * consistently enough to look deliberate.
         *
         * <p>Minutes from midnight cannot be converted by anything. This asserts
         * the reading survives the round trip rather than asserting the column
         * type, because the column type is only the current means to that end.
         */
        @Test
        @DisplayName("the working day survives the UTC jdbc.time_zone round trip unshifted")
        void theWorkingDayIsNotTimezoneShifted() {
            assertThat(repository.getCalendar().getWorkDayStart())
                    .as("a wall-clock boundary must not move with the JVM or session zone; "
                            + "under a TIME column on an IST machine this read 15:00")
                    .isEqualTo(LocalTime.of(9, 30));
            assertThat(rawStartMinutes())
                    .as("and the stored form is a plain minute count, 09:30 = 570")
                    .isEqualTo(570);
        }

        /**
         * The regression test for the defect this task fixed. Under the old
         * contract — "ISO day numbers", constrained 0–6, mocked {@code [0, 6]} —
         * a backend reading through {@code DayOfWeek} would find Sunday
         * workable, and every SLA spanning a weekend would be short by a day.
         */
        @Test
        @DisplayName("treats Sunday as non-working, which the JS 0-based reading would not")
        void sundayIsNonWorking() {
            WorkingCalendar calendar = repository.getCalendar();

            assertThat(calendar.isNonWorkingDay(DayOfWeek.SUNDAY)).isTrue();
            assertThat(calendar.isNonWorkingDay(DayOfWeek.SATURDAY)).isTrue();
            assertThat(calendar.isNonWorkingDay(DayOfWeek.MONDAY)).isFalse();
            assertThat(calendar.isNonWorkingDay(DayOfWeek.FRIDAY)).isFalse();
        }

        @Test
        @DisplayName("stores the day numbers, not the enum names Jackson would prefer")
        void storesNumbersNotNames() {
            String raw = db.sql("SELECT CAST(weekly_off AS CHAR) FROM working_calendar WHERE id = 1")
                    .query(String.class).single();

            assertThat(raw)
                    .as("the JSON Schema check would reject [\"SATURDAY\",\"SUNDAY\"], so this "
                            + "also proves the entity is not serialising the enum")
                    .contains("6", "7")
                    .doesNotContain("SATURDAY", "SUNDAY");
        }
    }

    // ------------------------------------------------------------------
    // The constraints
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("ck_working_calendar_weekly_off")
    class WeeklyOffConstraint {

        /** The exact value the old contract advertised. */
        @Test
        @DisplayName("rejects 0 — the JavaScript Sunday")
        void rejectsZero() {
            assertThatThrownBy(() -> update("[0, 6]"))
                    .hasMessageContaining("ck_working_calendar_weekly_off");
        }

        @Test
        @DisplayName("rejects 8 — past the end of the week")
        void rejectsEight() {
            assertThatThrownBy(() -> update("[8]"))
                    .hasMessageContaining("ck_working_calendar_weekly_off");
        }

        @Test
        @DisplayName("rejects a duplicated day")
        void rejectsDuplicates() {
            assertThatThrownBy(() -> update("[6, 6]"))
                    .hasMessageContaining("ck_working_calendar_weekly_off");
        }

        /**
         * A seven-day weekend would send {@code addWorkingHours} looking for a
         * working day that does not exist — an infinite loop in the service
         * every SLA figure routes through, caused by a data edit.
         */
        @Test
        @DisplayName("rejects all seven days off")
        void rejectsAWeekWithNoWorkingDay() {
            assertThatThrownBy(() -> update("[1, 2, 3, 4, 5, 6, 7]"))
                    .hasMessageContaining("ck_working_calendar_weekly_off");
        }

        @Test
        @DisplayName("rejects a non-integer day")
        void rejectsAString() {
            assertThatThrownBy(() -> update("[\"SATURDAY\"]"))
                    .hasMessageContaining("ck_working_calendar_weekly_off");
        }

        @Test
        @DisplayName("accepts a six-day working week — Sunday only")
        void acceptsASingleDayOff() {
            update("[7]");

            assertThat(reread().getWeeklyOff()).containsExactly(DayOfWeek.SUNDAY);
        }

        @Test
        @DisplayName("accepts an empty pattern — every day workable")
        void acceptsNoDaysOff() {
            update("[]");

            assertThat(reread().getWeeklyOff()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the other constraints")
    class OtherConstraints {

        @Test
        @DisplayName("a second row cannot be inserted")
        void singletonIsEnforced() {
            assertThatThrownBy(() -> db.sql("""
                    INSERT INTO working_calendar
                      (id, weekly_off, work_day_start_min, work_day_end_min, timezone)
                    VALUES (2, CAST('[6, 7]' AS JSON), 540, 1020, 'UTC')
                    """).update())
                    .as("an org has one working week; two rows would mean two answers")
                    .hasMessageContaining("ck_working_calendar_singleton");
        }

        @Test
        @DisplayName("a working day cannot end before it starts")
        void dayBoundsAreOrdered() {
            assertThatThrownBy(() -> db.sql(
                    "UPDATE working_calendar SET work_day_start_min = 1110, "
                            + "work_day_end_min = 570 WHERE id = 1").update())
                    .hasMessageContaining("ck_working_calendar_day_bounds");
        }

        @Test
        @DisplayName("a boundary cannot fall outside the day")
        void boundariesStayInsideTheDay() {
            assertThatThrownBy(() -> db.sql(
                    "UPDATE working_calendar SET work_day_end_min = 1441 WHERE id = 1").update())
                    .hasMessageContaining("ck_working_calendar_end_in_day");

            assertThatThrownBy(() -> db.sql(
                    "UPDATE working_calendar SET work_day_start_min = -1 WHERE id = 1").update())
                    .hasMessageContaining("ck_working_calendar_start_in_day");
        }

        @Test
        @DisplayName("a day may close at midnight — 1440, not 0")
        void midnightCloseIsAllowed() {
            db.sql("UPDATE working_calendar SET work_day_end_min = 1440 WHERE id = 1").update();

            assertThat(reread().getWorkDayEnd())
                    .as("the last instant the day contains, not 00:00 at its start")
                    .isEqualTo(LocalTime.MAX);
        }
    }

    // ------------------------------------------------------------------
    // The entity's own conversion
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("setWeeklyOff")
    class Writing {

        @Test
        @DisplayName("writes DayOfWeek back as the ISO numbers the CHECK accepts")
        void roundTripsThroughDayOfWeek() {
            WorkingCalendar calendar = repository.getCalendar();
            calendar.setWeeklyOff(EnumSet.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY));
            repository.saveAndFlush(calendar);

            assertThat(reread().getWeeklyOff())
                    .as("a Fri/Sat weekend, which several of our clients' regions use")
                    .containsExactlyInAnyOrder(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY);
            assertThat(rawWeeklyOff()).contains("5", "6");
        }

        @Test
        @DisplayName("sorts on write, so the stored array does not depend on Set iteration order")
        void writesInAStableOrder() {
            WorkingCalendar calendar = repository.getCalendar();
            calendar.setWeeklyOff(Set.of(DayOfWeek.SUNDAY, DayOfWeek.SATURDAY));
            repository.saveAndFlush(calendar);

            assertThat(rawWeeklyOff().replaceAll("\\s", "")).isEqualTo("[6,7]");
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Raw SQL rather than the entity, so the constraint is what rejects the value. */
    private void update(String weeklyOffJson) {
        db.sql("UPDATE working_calendar SET weekly_off = CAST(? AS JSON) WHERE id = 1")
                .param(weeklyOffJson)
                .update();
    }

    /**
     * Clears the persistence context first, deliberately. Without it, a reread
     * inside the same transaction returns the identical managed instance and the
     * assertion passes whether or not anything reached the database — which is
     * exactly the round trip these tests exist to check.
     */
    private WorkingCalendar reread() {
        em.clear();
        return repository.getCalendar();
    }

    private String rawWeeklyOff() {
        return db.sql("SELECT CAST(weekly_off AS CHAR) FROM working_calendar WHERE id = 1")
                .query(String.class).single();
    }

    private int rawStartMinutes() {
        return db.sql("SELECT work_day_start_min FROM working_calendar WHERE id = 1")
                .query(Integer.class).single();
    }
}
