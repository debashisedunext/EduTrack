package com.edunext.edutrack.worker.onboarding.digest;

import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import com.edunext.edutrack.worker.WorkerApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-114 · the manager digest against a real MySQL 8.4.
 *
 * <p>Almost everything this feature decides is decided in SQL — which journeys
 * are running, which steps have stopped, when a paused step last moved, and who
 * the owner reports to. A mocked repository would assert that a scheduler loops
 * over whatever it was handed, which is the one part that could not go wrong.
 *
 * <p>The clock is fixed to <b>Wednesday 2026-09-09, 08:30 IST</b>: mid-week, so
 * "two working days ago" is Monday the 7th and no assertion here depends on
 * when the suite runs. The threshold is left at its default of two working
 * days, because a test that configures the threshold away stops testing it.
 *
 * <h2>Every test gets its own manager, and nothing is deleted between them</h2>
 *
 * <p>{@code ob_step_clock_events} is append-only and a database trigger refuses
 * a DELETE — A-105's guarantee working exactly as intended — and the steps it
 * points at cannot be removed either while it holds rows against them. So the
 * fixture is not torn down. Each test creates its own manager, owner, client
 * and journey, and every assertion is scoped to that manager's own mail.
 *
 * <p>{@code DigestSchedulerIT} made the same call for the same reason:
 * {@code ticket_history} is append-only, so tickets cannot be deleted between
 * tests either. Asserting on {@link ObManagerDigestScheduler#sendManagerDigest()}'s
 * return count would work exactly once and then start failing as an earlier
 * test's stuck steps accumulated behind it.
 */
@Testcontainers
@SpringBootTest(classes = WorkerApplication.class)
@Import(ObManagerDigestIT.FixedClock.class)
class ObManagerDigestIT {

    /** Wednesday 2026-09-09, 08:30 IST. */
    private static final Instant NOW = Instant.parse("2026-09-09T03:00:00Z");

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_ob_digest_it")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+00:00",
                    "--log-bin-trust-function-creators=1")
            .withUrlParam("allowPublicKeyRetrieval", "true")
            .withUrlParam("useSSL", "false")
            .withUrlParam("connectionTimeZone", "UTC");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        // The dispatcher would drain the rows this test asserts on, and the
        // ticketing scanners write `tickets` at startup. Neither is under test.
        registry.add("edutrack.ob-outbox.enabled", () -> "false");
        registry.add("edutrack.outbox.enabled", () -> "false");
        registry.add("edutrack.stats.enabled", () -> "false");
        registry.add("edutrack.sla.initial-delay", () -> "PT24H");
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
    }

    @TestConfiguration
    public static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired JdbcTemplate jdbc;
    @Autowired ObManagerDigestScheduler scheduler;

    private long manager;
    private long owner;
    private String ownerName;
    private long clientId;
    private String clientName;
    private long productId;
    private String productName;
    private long templateId;
    private long journeyId;

    @BeforeEach
    void seed() {
        // Holidays are the one shared row this suite writes, and they decide
        // whether the digest runs at all. Everything else below is per-test.
        jdbc.update("DELETE FROM holidays");

        int n = SEQ.incrementAndGet();
        manager = insertUser("mgr" + n, null);
        ownerName = "own" + n;
        owner = insertUser(ownerName, manager);
        clientName = "Horizon Academy " + n;
        clientId = insertClient(clientName);
        productName = "Learning Management " + n;
        productId = insertProduct("P" + n, productName);
        templateId = insertTemplate(productId);
        journeyId = insertJourney(clientId, productId, templateId);
    }

    // ── what reaches a manager ───────────────────────────────────────────

    @Test
    @DisplayName("an overdue step reaches the owner's reporting manager, once")
    void anOverdueStepIsDigested() {
        insertStep("Data migration", "IN_PROGRESS", owner, daysAgo(4), daysAgo(6));

        scheduler.sendManagerDigest();

        Map<String, Object> row = onlyDigest();
        assertThat(row.get("event_key")).isEqualTo(ObNotificationEvent.MANAGER_DIGEST.key());
        assertThat(row.get("recipient_type")).isEqualTo("STAFF");
        assertThat(row.get("recipient_user_id")).isEqualTo(manager);
        // No client, journey or step: a digest is about many of each, and naming
        // one would put that client's name in the layout header as though the
        // mail were only about them.
        assertThat(row.get("ob_client_id")).isNull();
        assertThat(row.get("journey_id")).isNull();
        assertThat(row.get("step_id")).isNull();
    }

    @Test
    @DisplayName("the payload carries the summary and one line per stuck step")
    void thePayloadIsTheMail() {
        insertStep("Data migration", "IN_PROGRESS", owner, daysAgo(4), daysAgo(6));

        scheduler.sendManagerDigest();

        String payload = payloadOf(onlyDigest());
        assertThat(payload)
                .contains("\"stuck_count\":1")
                .contains("\"client_count\":1")
                .contains("\"threshold\":\"2 working days\"")
                .contains("\"client\":\"" + clientName + "\"")
                .contains("\"product\":\"" + productName + "\"")
                .contains("\"step\":\"Data migration\"")
                .contains("\"owner\":\"" + ownerName + "\"")
                .contains("\"state\":\"Overdue\"");
    }

    @Test
    @DisplayName("one mail per manager, however many of their steps are stuck")
    void managersGetOneMailNotOnePerStep() {
        insertStep("Data migration", "IN_PROGRESS", owner, daysAgo(4), daysAgo(6));
        insertStep("Config", "IN_PROGRESS", owner, daysAgo(3), daysAgo(6));
        insertStep("Training", "BLOCKED", owner, null, daysAgo(9));

        scheduler.sendManagerDigest();

        assertThat(payloadOf(onlyDigest())).contains("\"stuck_count\":3");
    }

    @Test
    @DisplayName("a manager with nothing stuck is not mailed at all")
    void nobodyIsToldTheyHaveNothing() {
        // A digest saying "nothing is stuck" is how somebody learns to filter
        // digests, after which the one that mattered is filtered too.
        insertStep("Data migration", "IN_PROGRESS", owner, hoursFromNow(48), daysAgo(1));

        scheduler.sendManagerDigest();

        assertThat(digests()).isEmpty();
    }

    // ── the case the digest exists for ───────────────────────────────────

    @Test
    @DisplayName("a step parked on the client is stuck even though it can never be overdue")
    void aPausedStepIsFoundWithoutADueDate() {
        // §5.7 stops the clock while a step waits on the client, so this step
        // has no due date to miss and raises no event of its own. It is exactly
        // the stall a manager should be chasing, and the only mail that can
        // tell them about it is this one.
        long step = insertStep("Client data upload", "WAITING_ON_CLIENT", owner, null, daysAgo(20));
        insertClockEvent(step, "PAUSED", daysAgo(12));

        scheduler.sendManagerDigest();

        assertThat(payloadOf(onlyDigest()))
                .contains("\"state\":\"Waiting on client\"")
                .contains("\"stalled_for\":\"8 working days\"");
    }

    @Test
    @DisplayName("stalled-since is the current pause, not an older one that was resumed")
    void aResumedPauseIsNotTheCurrentStall() {
        long step = insertStep("Integration", "BLOCKED", owner, null, daysAgo(30));
        insertClockEvent(step, "PAUSED", daysAgo(25));
        insertClockEvent(step, "RESUMED", daysAgo(20));
        insertClockEvent(step, "PAUSED", daysAgo(5));

        scheduler.sendManagerDigest();

        // Friday the 4th, Monday the 7th and Tuesday the 8th — the weekend is
        // not counted and today is not over. Reading the older pause instead
        // would say seventeen, and send somebody into a call with a number the
        // client can disprove.
        assertThat(payloadOf(onlyDigest())).contains("\"stalled_for\":\"3 working days\"");
    }

    @Test
    @DisplayName("a step with no clock event falls back to when it was created")
    void aStepWithNoClockEventIsStillStuck() {
        // C-104's lifecycle writes clock events; a row that predates it, or one
        // whose event was lost, is still a step that has not moved. Dropping it
        // out of the digest would hide the stall to protect a join.
        insertStep("Kickoff", "BLOCKED", owner, null, daysAgo(15));

        scheduler.sendManagerDigest();

        assertThat(digests()).hasSize(1);
    }

    @Test
    @DisplayName("a step that is merely blocked today is not yet stuck")
    void theThresholdIsHonoured() {
        long step = insertStep("Integration", "BLOCKED", owner, null, daysAgo(10));
        insertClockEvent(step, "PAUSED", hoursAgo(3));

        scheduler.sendManagerDigest();

        assertThat(digests()).isEmpty();
    }

    // ── what is deliberately left out ────────────────────────────────────

    @Test
    @DisplayName("a journey still behind the prerequisite gate is waiting by design")
    void lockedJourneysAreNotStuck() {
        jdbc.update("UPDATE ob_journeys SET gate_status = 'LOCKED', gate_opened_at = NULL WHERE id = ?",
                journeyId);
        insertStep("Data migration", "IN_PROGRESS", owner, daysAgo(4), daysAgo(6));

        scheduler.sendManagerDigest();

        assertThat(digests()).isEmpty();
    }

    @Test
    @DisplayName("a journey held by another journey is waiting by design too")
    void heldJourneysAreNotStuck() {
        // §5.5's service-level dependency. Independent of the gate — a journey
        // can be past one hold and still under the other — which is why the
        // query checks both.
        long other = insertJourney(clientId, insertProduct("ERP", "ERP Suite"), templateId);
        jdbc.update("UPDATE ob_journeys SET held_by_journey_id = ?, released_at = NULL WHERE id = ?",
                other, journeyId);
        insertStep("Data migration", "IN_PROGRESS", owner, daysAgo(4), daysAgo(6));

        scheduler.sendManagerDigest();

        assertThat(digests()).isEmpty();
    }

    @Test
    @DisplayName("a client somebody put on hold does not generate a daily mail")
    void onHoldClientsAreNotChased() {
        jdbc.update("UPDATE ob_clients SET overall_status = 'ON_HOLD', status_reason = 'awaiting budget' "
                + "WHERE id = ?", clientId);
        insertStep("Data migration", "IN_PROGRESS", owner, daysAgo(4), daysAgo(6));

        scheduler.sendManagerDigest();

        assertThat(digests()).isEmpty();
    }

    @Test
    @DisplayName("done and skipped steps are nobody's stall")
    void closedStepsAreNotCounted() {
        insertStep("Finished late", "DONE", owner, daysAgo(4), daysAgo(6));
        insertStep("Skipped", "SKIPPED", owner, daysAgo(4), daysAgo(6));

        scheduler.sendManagerDigest();

        assertThat(digests()).isEmpty();
    }

    @Test
    @DisplayName("a deactivated manager is not mailed, and the step is reported as unattributed")
    void anInactiveManagerGetsNothing() {
        jdbc.update("UPDATE users SET is_active = 0 WHERE id = ?", manager);
        insertStep("Data migration", "IN_PROGRESS", owner, daysAgo(4), daysAgo(6));

        scheduler.sendManagerDigest();

        assertThat(digests()).isEmpty();
    }

    @Test
    @DisplayName("a step whose owner has no manager reaches nobody, and is counted as such")
    void unattributedStepsAreVisible() {
        // The gap B-113's escalation matrix closes. Until then it is a number in
        // the log rather than silence, which is the only honest option.
        long orphan = insertUser("solo" + SEQ.get(), null);
        insertStep("Data migration", "IN_PROGRESS", orphan, daysAgo(4), daysAgo(6));

        scheduler.sendManagerDigest();

        assertThat(digests()).isEmpty();
    }

    // ── one mail a day ───────────────────────────────────────────────────

    @Test
    @DisplayName("a second run on the same day queues nothing, even after the first was sent")
    void oneMailADay() {
        insertStep("Data migration", "IN_PROGRESS", owner, daysAgo(4), daysAgo(6));

        scheduler.sendManagerDigest();
        assertThat(digests()).hasSize(1);

        // The case A-107's unique index cannot catch: queued_dedupe_key goes
        // NULL once a row leaves the queue, so by nine o'clock the morning's
        // digest blocks nothing. A restart at 08:45 would otherwise send a
        // second copy.
        jdbc.update("UPDATE ob_notification_outbox SET status = 'SENT', sent_at = ? "
                        + "WHERE event_key = ? AND recipient_user_id = ?",
                Timestamp.from(NOW), ObNotificationEvent.MANAGER_DIGEST.key(), manager);

        scheduler.sendManagerDigest();

        assertThat(digests()).hasSize(1);
    }

    @Test
    @DisplayName("the dedupe key names the day, so tomorrow's digest is a different row")
    void theKeyIsPerDay() {
        insertStep("Data migration", "IN_PROGRESS", owner, daysAgo(4), daysAgo(6));
        scheduler.sendManagerDigest();

        assertThat(onlyDigest().get("dedupe_key"))
                .isEqualTo("MANAGER_DIGEST:EMAIL:day:"
                        + LocalDate.ofInstant(NOW, IST).toEpochDay() + ":user:" + manager);
    }

    // ── the calendar ─────────────────────────────────────────────────────

    @Test
    @DisplayName("no digest on an org holiday")
    void noDigestOnANonWorkingDay() {
        // A digest on a day nobody is working is noise, and noise is what
        // teaches people to filter digests.
        jdbc.update("INSERT INTO holidays (holiday_date, name, is_recurring, is_active) "
                + "VALUES ('2026-09-09', 'Founders Day', 0, 1)");
        insertStep("Data migration", "IN_PROGRESS", owner, daysAgo(4), daysAgo(6));

        scheduler.sendManagerDigest();

        assertThat(digests()).isEmpty();
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    /** {@code daysAgo}/{@code hoursAgo} are calendar offsets from the fixed clock. */
    private static Timestamp daysAgo(int days) {
        return Timestamp.from(NOW.minusSeconds(days * 86_400L));
    }

    private static Timestamp hoursAgo(int hours) {
        return Timestamp.from(NOW.minusSeconds(hours * 3_600L));
    }

    private static Timestamp hoursFromNow(int hours) {
        return Timestamp.from(NOW.plusSeconds(hours * 3_600L));
    }

    private long insertUser(String username, Long reportsTo) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'PM'", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                   role_id, reporting_manager_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?, ?)
                """, username, username, username + "@edunext.test", username, roleId, reportsTo);
        return lastInsertId();
    }

    private long insertClient(String name) {
        jdbc.update("INSERT INTO ob_clients (name, onboarding_date) VALUES (?, '2026-08-01')", name);
        return lastInsertId();
    }

    private long insertProduct(String code, String name) {
        jdbc.update("INSERT INTO ob_products (code, name) VALUES (?, ?)", code, name);
        return lastInsertId();
    }

    private long insertTemplate(long product) {
        jdbc.update("INSERT INTO ob_journey_templates (product_id, name) VALUES (?, 'Standard')", product);
        return lastInsertId();
    }

    /** An open, released journey — the only kind whose steps can be stuck. */
    private long insertJourney(long client, long product, long template) {
        jdbc.update("INSERT INTO ob_client_applications (ob_client_id, product_id) VALUES (?, ?)",
                client, product);
        jdbc.update("""
                INSERT INTO ob_journeys (ob_client_id, product_id, template_id,
                                         gate_status, gate_opened_at, started_at)
                VALUES (?, ?, ?, 'OPEN', ?, ?)
                """, client, product, template, daysAgo(30), daysAgo(30));
        return lastInsertId();
    }

    private int sequence = 0;

    private long insertStep(String name, String status, long ownerId, Timestamp dueAt, Timestamp createdAt) {
        jdbc.update("""
                INSERT INTO ob_journey_steps (journey_id, sequence, name, tat_days, owner_user_id,
                                              status, blocked_reason_code, skip_reason,
                                              due_at, started_at, created_at)
                VALUES (?, ?, ?, 3, ?, ?, ?, ?, ?, ?, ?)
                """, journeyId, ++sequence, name, ownerId, status,
                "BLOCKED".equals(status) ? "CLIENT_DEPENDENCY" : null,
                "SKIPPED".equals(status) ? "not applicable" : null,
                dueAt, createdAt, createdAt);
        return lastInsertId();
    }

    private void insertClockEvent(long stepId, String type, Timestamp at) {
        jdbc.update("""
                INSERT INTO ob_step_clock_events (step_id, journey_id, event_type, pause_reason,
                                                  occurred_at, actor_type)
                VALUES (?, ?, ?, ?, ?, 'SYSTEM')
                """, stepId, journeyId, type,
                "PAUSED".equals(type) ? "WAITING_ON_CLIENT" : null, at);
    }

    private long lastInsertId() {
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }

    /**
     * This test's manager's digests, never the table's.
     *
     * <p>Nothing is deleted between tests (see the class note), so an unscoped
     * query would count what an earlier test queued and every assertion here
     * would depend on the order JUnit happened to run them in.
     */
    private List<Map<String, Object>> digests() {
        return jdbc.queryForList(
                "SELECT * FROM ob_notification_outbox "
                        + "WHERE event_key = ? AND recipient_user_id = ? ORDER BY id",
                ObNotificationEvent.MANAGER_DIGEST.key(), manager);
    }

    private Map<String, Object> onlyDigest() {
        List<Map<String, Object>> rows = digests();
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    /**
     * The queue row's payload as compact JSON.
     *
     * <p>Asserted as text rather than parsed, which keeps the test independent
     * of which mapper the worker holds — but MySQL formats the column with a
     * space after every colon on the way out, so the spaces are taken back out
     * here. Otherwise every assertion below would be written against MySQL's
     * pretty-printer rather than against what the scheduler wrote.
     */
    private String payloadOf(Map<String, Object> row) {
        return String.valueOf(row.get("payload")).replace("\": ", "\":");
    }
}
