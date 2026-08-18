package com.edunext.edutrack.worker.sla;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-023 · the stage-SLA scanner, against a real schema.
 *
 * <p>Every assertion is scoped to the ticket the test created, never to
 * {@code scanOnce()}'s return value. Transitions cannot be deleted between
 * tests — A-008 rejects it — so segments accumulate across the class and a
 * global count is shared state: a pass that returns 1 may be announcing
 * somebody else's ticket, which makes a green test mean nothing and a red one
 * point at the wrong place.

/**
 * <b>{@code @Import(FixedClock.class)} is not decoration.</b> A nested
 * {@code @TestConfiguration} is auto-detected only when {@code @SpringBootTest}
 * declares no {@code classes}; this one does, so without the explicit import
 * the fixed clock is silently ignored and every assertion runs against the real
 * one. That is exactly how it was written first — the suite passed, and it
 * passed because the tickets were old enough to breach under any clock, not
 * because the clock was fixed.
 */
@Testcontainers
@SpringBootTest(classes = com.edunext.edutrack.worker.WorkerApplication.class)
@org.springframework.context.annotation.Import(StageSlaScannerIT.FixedClock.class)
class StageSlaScannerIT {

    /**
     * Monday 09:45 in the calendar's own zone.
     *
     * <p>B-023 seeds Asia/Kolkata, 09:30–18:30, so instants here are chosen in
     * IST and written in UTC: 04:15Z is 09:45 IST, a quarter of an hour into
     * the working week. Picking a UTC-looking "mid-morning" instead puts NOW at
     * 15:30 IST with six working hours already spent, which silently turns the
     * weekend case below into a breach and makes the test agree with a bug.
     */
    private static final Instant NOW = Instant.parse("2026-08-10T04:15:00Z");

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
        // The seven sla scanners are `@Scheduled(fixedDelay…)`, which fires its
        // first run the instant the context is up — seven threads scanning
        // `tickets` while this class's fixture is still writing it. That is a
        // deadlock reported against the test's own UPDATE, and it cost a re-run
        // on two integration batches. `SlaScanner` carries the full account.
        // Pushed past any suite's lifetime; every test here calls scanOnce().
        registry.add("edutrack.sla.initial-delay", () -> "PT24H");
        // A-051's stats worker is the other `fixedDelay` that fires at context
        // startup, and DailyStatsRepository reads and JOINs `tickets` — so it
        // races this class's fixture exactly as the sla scanners did. A-056 hit
        // the same thing from the other side ("22 of 25 cases failing in reset()")
        // and added this switch, applying it only to StatsRefreshIT. Using their
        // switch rather than editing worker/stats, which is Stream A's.
        registry.add("edutrack.stats.enabled", () -> "false");
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("edutrack.sla.stage-scan-interval", () -> "PT1H");
        registry.add("edutrack.sla.scan-interval", () -> "PT1H");
        registry.add("edutrack.outbox.enabled", () -> "false");
    }

    @TestConfiguration
    public static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StageSlaScanner scanner;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private long stageOwner;
    private long manager;
    private long projectManager;
    private long projectId;
    private long templateId;

    @BeforeEach
    void seed() {
        // Transitions and tickets are NOT cleaned between tests, and cannot
        // be: A-008 rejects DELETE on ticket_stage_transitions outright — "the
        // ribbon can never be rewritten" — which is the guarantee working, not
        // an obstacle. Each test therefore makes its own ticket and every
        // assertion is scoped to it. Announcements are not cleaned either, so
        // a segment announced by one test stays claimed and cannot inflate the
        // next one's count.
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM email_log");

        int n = SEQ.incrementAndGet();
        manager = insertUser("srm" + n, null);
        stageOwner = insertUser("sqa" + n, manager);
        projectManager = insertUser("spm" + n, null);
        projectId = insertProject("SP" + n, projectManager);
        templateId = insertTemplate("T" + n, "QA", 4.00);
    }

    // ------------------------------------------------------------ the point

    @Test
    @DisplayName("§16 3b: a ticket inside its PCD can still be rotting in one stage")
    void aStageBreachesEvenWhenTheTicketIsNotLate() {
        // Planned Close Date is a week away — D-020 would never look at this.
        long ticket = insertTicket("STG-1", NOW.plusSeconds(604800));
        enterStage(ticket, "QA", Instant.parse("2026-08-06T09:00:00Z"));

        scanner.scanOnce();

        assertThat(alertsFor(ticket)).isEqualTo(1);
        assertThat(isDelayed(ticket))
                .as("the ticket-level SLA is untouched — this is a different alarm")
                .isFalse();
        assertThat(notifiedUserIds(ticket)).contains(stageOwner);
    }

    @Test
    @DisplayName("a stage still inside its budget is not announced")
    void aFreshStageIsLeftAlone() {
        long ticket = insertTicket("STG-2", NOW.plusSeconds(604800));
        enterStage(ticket, "QA", NOW.minusSeconds(1800));

        scanner.scanOnce();

        assertThat(alertsFor(ticket)).isZero();
    }

    @Test
    @DisplayName("a sealed stage is not a stage anybody is sitting in")
    void aClosedSegmentIsIgnored() {
        long ticket = insertTicket("STG-3", NOW.plusSeconds(604800));
        long transition = enterStage(ticket, "QA", Instant.parse("2026-08-03T09:00:00Z"));
        jdbc.update("UPDATE ticket_stage_transitions SET exited_at = ?, is_current = 0 WHERE id = ?",
                java.sql.Timestamp.from(NOW.minusSeconds(3600)), transition);

        scanner.scanOnce();

        assertThat(alertsFor(ticket)).isZero();
    }

    @Test
    @DisplayName("a stage with no SLA configured never breaches")
    void aStageWithoutABudgetIsSkipped() {
        long noSla = insertTemplate("N" + SEQ.incrementAndGet(), "QA", null);
        long ticket = insertTicketOn(noSla, "STG-4", NOW.plusSeconds(604800));
        enterStage(ticket, "QA", Instant.parse("2026-08-03T09:00:00Z"));

        scanner.scanOnce();

        assertThat(alertsFor(ticket)).isZero();
    }

    // --------------------------------------------------------- D-027 again

    @Test
    @DisplayName("the stage budget is working hours, so a weekend does not spend it")
    void aWeekendDoesNotConsumeTheStageSla() {
        // Handed to QA on Friday evening with a 4-working-hour SLA. By Monday
        // morning the wall clock says ~63 hours; the working calendar says a
        // quarter of one.
        // Comparing wall-clock elapsed against a working-hours budget is the
        // Friday-evening bug from §5, one level down.
        long ticket = insertTicket("STG-5", NOW.plusSeconds(604800));
        // 19:00 IST on Friday — after the working day closed at 18:30.
        enterStage(ticket, "QA", Instant.parse("2026-08-07T13:30:00Z"));

        scanner.scanOnce();

        assertThat(alertsFor(ticket))
                .as("a quarter of a working hour has passed, against a 4-hour SLA")
                .isZero();
    }

    @Test
    @DisplayName("the alert quotes elapsed working hours and the budget")
    void theAlertSaysHowFarOver() {
        long ticket = insertTicket("STG-6", NOW.plusSeconds(604800));
        enterStage(ticket, "QA", Instant.parse("2026-08-06T09:00:00Z"));

        scanner.scanOnce();

        String body = jdbc.queryForObject(
                "SELECT body FROM notifications WHERE ticket_id = ? LIMIT 1", String.class, ticket);
        assertThat(body).contains("working hours in this stage against a 4.00-hour SLA");
    }

    // ------------------------------------------------------------- once only

    @Test
    @DisplayName("a stuck stage is announced once, not every fifteen minutes")
    void announcementHappensOnce() {
        long ticket = insertTicket("STG-7", NOW.plusSeconds(604800));
        enterStage(ticket, "QA", Instant.parse("2026-08-06T09:00:00Z"));
        scanner.scanOnce();
        int after = countNotifications();

        scanner.scanOnce();

        assertThat(alertsFor(ticket)).isEqualTo(1);
        assertThat(countNotifications()).isEqualTo(after);
    }

    @Test
    @DisplayName("re-entering the same stage is a new segment and can breach again")
    void asecondVisitToAStageIsItsOwnBreach() {
        long ticket = insertTicket("STG-8", NOW.plusSeconds(604800));
        long first = enterStage(ticket, "QA", Instant.parse("2026-08-05T09:00:00Z"));
        scanner.scanOnce();

        // QA failed, back to DEV, then returned to QA — a rework loop, which is
        // exactly the case §16 3b cares about.
        jdbc.update("UPDATE ticket_stage_transitions SET exited_at = ?, is_current = 0 WHERE id = ?",
                java.sql.Timestamp.from(Instant.parse("2026-08-05T12:00:00Z")), first);
        enterStage(ticket, "QA", Instant.parse("2026-08-06T09:00:00Z"));

        scanner.scanOnce();

        assertThat(alertsFor(ticket))
                .as("one announcement per segment, not per ticket")
                .isEqualTo(2);
    }

    // ------------------------------------------------------------- who hears

    @Test
    @DisplayName("the stage owner is told, not the ticket assignee")
    void theStageOwnerIsTheOneWhoCanUnstickIt() {
        long otherAssignee = insertUser("other" + SEQ.incrementAndGet(), null);
        long ticket = insertTicket("STG-9", NOW.plusSeconds(604800));
        jdbc.update("UPDATE tickets SET assigned_to = ? WHERE id = ?", otherAssignee, ticket);
        enterStage(ticket, "QA", Instant.parse("2026-08-06T09:00:00Z"));

        scanner.scanOnce();

        // The person who can move it is whoever it was handed to.
        assertThat(notifiedUserIds(ticket))
                .contains(stageOwner)
                .doesNotContain(otherAssignee);
    }

    @Test
    void theStageOwnersManagerAndTheProjectManagerAreToldToo() {
        long ticket = insertTicket("STG-10", NOW.plusSeconds(604800));
        enterStage(ticket, "QA", Instant.parse("2026-08-06T09:00:00Z"));

        scanner.scanOnce();

        assertThat(notifiedUserIds(ticket))
                .containsExactlyInAnyOrder(stageOwner, manager, projectManager)
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("an unowned stage still records the breach")
    void aQueuedStageWithNoOwnerIsStillAnnounced() {
        long ticket = insertTicket("STG-11", NOW.plusSeconds(604800));
        // Inserted unowned rather than updated to unowned: A-008 permits
        // exactly one mutation on this table — sealing — and rejects the rest.
        enterStageUnowned(ticket, "QA", Instant.parse("2026-08-06T09:00:00Z"));

        scanner.scanOnce();

        assertThat(alertsFor(ticket)).isEqualTo(1);
        assertThat(notifiedUserIds(ticket)).containsExactly(projectManager);
    }

    // ------------------------------------------------------------- helpers

    private long insertTicket(String code, Instant plannedClose) {
        return insertTicketOn(templateId, code, plannedClose);
    }

    private long insertTicketOn(long template, String code, Instant plannedClose) {
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, task_type_id, level,
                                     original_level, status, current_stage, reported_by,
                                     assigned_to, planned_close_date, date_reported,
                                     workflow_template_id)
                SELECT ?, ?, ?, MIN(tt.id), 'HIGH', 'HIGH', 'OPEN', 'QA', ?, ?, ?, ?, ?
                  FROM task_types tt
                """, code, projectId, "Stage fixture " + code, projectManager, stageOwner,
                java.sql.Timestamp.from(plannedClose),
                java.sql.Timestamp.from(NOW.minusSeconds(864000)), template);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** A segment queued to a stage with nobody holding it. */
    private long enterStageUnowned(long ticketId, String stage, Instant enteredAt) {
        return insertSegment(ticketId, stage, enteredAt, null);
    }

    /** One open segment: entered, never exited. */
    private long enterStage(long ticketId, String stage, Instant enteredAt) {
        return insertSegment(ticketId, stage, enteredAt, stageOwner);
    }

    private long insertSegment(long ticketId, String stage, Instant enteredAt, Long owner) {
        Integer seq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq_no), 0) + 1 FROM ticket_stage_transitions WHERE ticket_id = ?",
                Integer.class, ticketId);
        jdbc.update("""
                INSERT INTO ticket_stage_transitions
                       (ticket_id, cycle_no, seq_no, from_stage, to_stage, to_user_id,
                        action_code, entered_at, is_current)
                VALUES (?, 1, ?, 'DEVELOPMENT', ?, ?, 'FORWARD', ?, 1)
                """, ticketId, seq, stage, owner, java.sql.Timestamp.from(enteredAt));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertTemplate(String code, String stage, Double slaHours) {
        jdbc.update("INSERT INTO workflow_templates (name, is_active) VALUES (?, 1)",
                "Template " + code);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO workflow_stages (template_id, seq, stage_code, display_name,
                                             owner_role, sla_hours)
                VALUES (?, 1, ?, ?, 'QA', ?)
                """, id, stage, stage, slaHours);
        return id;
    }

    private long insertProject(String code, Long managerId) {
        jdbc.update("INSERT INTO projects (project_code, name, manager_id) VALUES (?, ?, ?)",
                code, "Project " + code, managerId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertUser(String username, Long reportingManagerId) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles ORDER BY id LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                   role_id, reporting_manager_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?, ?)
                """, username, username, username + "@edunext.test", username,
                roleId, reportingManagerId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private boolean isDelayed(long ticketId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_delayed FROM tickets WHERE id = ?", Boolean.class, ticketId));
    }

    private List<Long> notifiedUserIds(long ticketId) {
        return jdbc.queryForList("""
                SELECT user_id FROM notifications
                 WHERE ticket_id = ? AND event_code = 'STAGE_SLA_BREACHED'
                """, Long.class, ticketId);
    }

    private int alertsFor(long ticketId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM stage_sla_alerts a
                  JOIN ticket_stage_transitions tr ON tr.id = a.transition_id
                 WHERE tr.ticket_id = ?
                """, Integer.class, ticketId);
        return n == null ? 0 : n;
    }

    private int countNotifications() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class);
        return n == null ? 0 : n;
    }
}
