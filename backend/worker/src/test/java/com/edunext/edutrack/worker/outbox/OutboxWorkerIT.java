package com.edunext.edutrack.worker.outbox;

import com.edunext.edutrack.domain.mail.EmailSuppressions;
import com.edunext.edutrack.domain.outbox.NewMail;
import com.edunext.edutrack.domain.outbox.OutboxEnqueuer;
import com.edunext.edutrack.worker.WorkerApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-010 · the outbox, proven against a real MySQL 8.4.
 *
 * <p>{@code SKIP LOCKED}, lease expiry and rollback atomicity are all
 * behaviours of the database rather than of the Java, so a mocked repository
 * would assert nothing that matters. The scheduler is switched off
 * ({@code edutrack.outbox.enabled=false}) and each test drives
 * {@link OutboxWorker#pollOnce()} by hand — otherwise the poller drains rows
 * out from under the assertions.
 */
@Testcontainers
@SpringBootTest(classes = WorkerApplication.class)
class OutboxWorkerIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_outbox_it")
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
        // The seven sla scanners are `@Scheduled(fixedDelay…)`, which fires its
        // first run the instant the context is up — seven threads scanning
        // `tickets` while this class's fixture is still writing it. That is a
        // deadlock reported against the test's own UPDATE, and it cost a re-run
        // on two integration batches. `SlaScanner` carries the full account.
        // Pushed past any suite's lifetime; every test here calls scanOnce().
        registry.add("edutrack.sla.initial-delay", () -> "PT24H");
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        // The worker leaves migration to api in production; the test has no
        // api, so it builds the schema itself from the same migrations.
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("edutrack.outbox.enabled", () -> false);
        // ⚠️ A-056 · Stream A's line in Stream D's file — flagged for Debashis.
        //
        // Without it this suite is red, and had been before A-056 touched
        // anything: 22 of its 25 cases fail in reset() on
        //
        //   DELETE FROM projects — a foreign key constraint fails
        //   (`daily_ticket_stats`, CONSTRAINT `fk_daily_stats_project`)
        //
        // A-051's stats scheduler is a @Scheduled fixedDelay, which fires once
        // at context startup regardless of its interval, and it summarises
        // every project into daily_ticket_stats. This suite's reset() deletes
        // projects and knows nothing about that table, so the FK holds the rows
        // it never created. Exactly the race PR #147 diagnosed; the switch it
        // added for it was applied only to StatsRefreshIT, and this suite was
        // left exposed.
        //
        // The same shape as `edutrack.outbox.enabled` above, and for the same
        // reason: a background schedule writing rows underneath a test that is
        // trying to control its own fixtures. Fixed here rather than by adding
        // daily_ticket_stats to reset(), which would make every future summary
        // table one more line every worker IT has to remember.
        registry.add("edutrack.stats.enabled", () -> "false");
    }

    @Autowired
    OutboxRepository repository;

    @Autowired
    OutboxEnqueuer enqueuer;

    @Autowired
    MailFailureNotifier failureNotifier;

    @Autowired
    EmailSuppressions suppressions;

    @Autowired
    OutboxProperties shippedProperties;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    private MutableClock clock;

    @BeforeEach
    void reset() {
        // Child rows first: notifications and email_log reference users and
        // tickets, tickets reference projects.
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM email_log");
        jdbc.update("DELETE FROM tickets");
        jdbc.update("DELETE FROM projects");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM email_suppressions");
        clock = new MutableClock(Instant.parse("2026-08-07T10:00:00Z"));
    }

    // ---------------------------------------------------------------- claim

    @Test
    void claimsOnlyRowsThatAreQueuedAndDue() {
        long due = insert("QUEUED", clock.instant().minusSeconds(1));
        insert("QUEUED", clock.instant().plus(Duration.ofHours(1)));   // not yet due
        insert("SENT", clock.instant().minusSeconds(1));               // already sent
        insert("FAILED", clock.instant().minusSeconds(1));             // given up on

        List<OutboxMessage> claimed =
                repository.claimBatch(10, Duration.ofMinutes(2), clock.instant());

        assertThat(claimed).extracting(OutboxMessage::id).containsExactly(due);
    }

    @Test
    void aClaimedRowIsLeasedSoTheNextPollSkipsIt() {
        insert("QUEUED", clock.instant().minusSeconds(1));

        assertThat(repository.claimBatch(10, Duration.ofMinutes(2), clock.instant())).hasSize(1);
        assertThat(repository.claimBatch(10, Duration.ofMinutes(2), clock.instant())).isEmpty();

        // Once the lease lapses the row is claimable again — a worker killed
        // mid-send self-heals without a reaper.
        clock.advance(Duration.ofMinutes(3));
        assertThat(repository.claimBatch(10, Duration.ofMinutes(2), clock.instant())).hasSize(1);
    }

    /**
     * The horizontal-scaling guarantee: two workers polling at once must
     * partition the queue, never overlap on a row. Without {@code SKIP LOCKED}
     * the second claimer blocks on the first's locks and then reads the same
     * rows, and every message goes out twice.
     */
    @Test
    void concurrentClaimsNeverOverlap() throws Exception {
        int rows = 40;
        for (int i = 0; i < rows; i++) {
            insert("QUEUED", clock.instant().minusSeconds(1));
        }

        int workers = 4;
        var startTogether = new CountDownLatch(1);
        List<Long> allClaimed = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            for (int i = 0; i < workers; i++) {
                pool.submit(() -> {
                    startTogether.await();
                    // Drain rather than run a fixed number of batches: under
                    // contention a claim legitimately comes back short, and a
                    // fixed count would stop with rows still queued.
                    int consecutiveEmpty = 0;
                    while (allClaimed.size() < rows && consecutiveEmpty < 20) {
                        List<OutboxMessage> got =
                                repository.claimBatch(4, Duration.ofMinutes(2), clock.instant());
                        if (got.isEmpty()) {
                            consecutiveEmpty++;
                            Thread.onSpinWait();
                        } else {
                            consecutiveEmpty = 0;
                            got.forEach(m -> allClaimed.add(m.id()));
                        }
                    }
                    return null;
                });
            }
            startTogether.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        Set<Long> distinct = new HashSet<>(allClaimed);
        assertThat(allClaimed)
                .as("a row claimed twice would be a mail delivered twice")
                .hasSameSizeAs(distinct);
        assertThat(distinct).hasSize(rows);
    }

    // ----------------------------------------------------------- send + stamp

    @Test
    void aSuccessfulSendIsStampedSent() {
        long id = insert("QUEUED", clock.instant().minusSeconds(1));

        int processed = workerWith(m -> new SendOutcome.Sent("provider-abc")).pollOnce();

        assertThat(processed).isEqualTo(1);
        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("provider_msg_id")).isEqualTo("provider-abc");
        assertThat(row.get("retry_count")).as("sent first time — zero retries").isEqualTo(0);
        assertThat(row.get("sent_at")).isNotNull();
        assertThat(row.get("error_text")).isNull();
    }

    @Test
    void aTransientFailureIsRescheduledWithExponentialBackoff() {
        long id = insert("QUEUED", clock.instant().minusSeconds(1));
        OutboxWorker worker = workerWith(m -> new SendOutcome.TransientFailure("smtp refused"));

        worker.pollOnce();

        Map<String, Object> afterFirst = row(id);
        assertThat(afterFirst.get("status")).isEqualTo("QUEUED");
        assertThat(afterFirst.get("retry_count")).isEqualTo(1);
        assertThat(afterFirst.get("error_text")).isEqualTo("smtp refused");
        // base 1m × 2^0
        assertThat(nextAttempt(id)).isEqualTo(clock.instant().plus(Duration.ofMinutes(1)));

        // Second attempt doubles to 2m, measured from the retry's own clock.
        clock.advance(Duration.ofMinutes(2));
        worker.pollOnce();
        assertThat(row(id).get("retry_count")).isEqualTo(2);
        assertThat(nextAttempt(id)).isEqualTo(clock.instant().plus(Duration.ofMinutes(2)));
    }

    @Test
    void retriesAreExhaustedIntoFailed() {
        long id = insert("QUEUED", clock.instant().minusSeconds(1));
        OutboxWorker worker = workerWith(m -> new SendOutcome.TransientFailure("smtp down"));

        // max-attempts is 3 for this worker, so the third try is the last.
        for (int attempt = 0; attempt < 3; attempt++) {
            worker.pollOnce();
            clock.advance(Duration.ofHours(1));
        }

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("retry_count")).isEqualTo(3);
        assertThat(row.get("error_text")).isEqualTo("smtp down");
    }

    @Test
    void aPermanentFailureIsNotRetried() {
        long id = insert("QUEUED", clock.instant().minusSeconds(1));

        workerWith(m -> new SendOutcome.PermanentFailure("invalid address")).pollOnce();

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("retry_count")).isEqualTo(1);
    }

    @Test
    void aTransportThatThrowsIsTreatedAsTransient() {
        long id = insert("QUEUED", clock.instant().minusSeconds(1));

        workerWith(m -> {
            throw new IllegalStateException("bug in transport");
        }).pollOnce();

        // A bug must not consume the message.
        assertThat(row(id).get("status")).isEqualTo("QUEUED");
        assertThat(row(id).get("retry_count")).isEqualTo(1);
    }

    // --------------------------------------- D-033 · failure is not silent

    /**
     * Blueprint §17 wants a missed alert "provable rather than deniable". The
     * {@code email_log} row already proves it to anyone who looks; this is what
     * tells the person who was waiting on the mail.
     */
    @Test
    void exhaustedRetriesNotifyTheIntendedRecipientInApp() {
        long assignee = insertUser("ravi", "DEVELOPER");
        insert("QUEUED", clock.instant().minusSeconds(1), assignee);
        OutboxWorker worker = workerWith(m -> new SendOutcome.TransientFailure("smtp down"));

        for (int attempt = 0; attempt < 3; attempt++) {
            worker.pollOnce();
            clock.advance(Duration.ofHours(1));
        }

        List<Map<String, Object>> raised = notifications();
        assertThat(raised).hasSize(1);
        assertThat(raised.getFirst().get("user_id")).isEqualTo(assignee);
        assertThat(raised.getFirst().get("event_code")).isEqualTo("MAIL_DELIVERY_FAILED");
        assertThat((String) raised.getFirst().get("body"))
                .contains("dev@example.com")
                .contains("2 retries")
                .contains("smtp down");
    }

    @Test
    void aPermanentFailureNotifiesImmediatelyWithoutClaimingRetries() {
        long assignee = insertUser("ravi", "DEVELOPER");
        insert("QUEUED", clock.instant().minusSeconds(1), assignee);

        workerWith(m -> new SendOutcome.PermanentFailure("invalid address")).pollOnce();

        List<Map<String, Object>> raised = notifications();
        assertThat(raised).hasSize(1);
        assertThat((String) raised.getFirst().get("body"))
                .as("giving up on the first attempt means zero retries, not 'after 0 retries'")
                .doesNotContain("retries")
                .contains("invalid address");
    }

    /**
     * A client contact has no login, so there is no bell to put this in. It
     * goes to the Admins instead — the audience D-034 also alerts on a bounce.
     */
    @Test
    void aFailedClientContactMailFallsBackToAdmins() {
        long admin = insertUser("asha", "ADMIN");
        insertUser("ravi", "DEVELOPER");                    // must not be told
        insert("QUEUED", clock.instant().minusSeconds(1));  // to_user_id is null

        workerWith(m -> new SendOutcome.PermanentFailure("mailbox unavailable")).pollOnce();

        assertThat(notifications())
                .singleElement()
                .extracting(row -> row.get("user_id"))
                .isEqualTo(admin);
    }

    @Test
    void aRetryThatHasNotExhaustedYetNotifiesNobody() {
        long assignee = insertUser("ravi", "DEVELOPER");
        insert("QUEUED", clock.instant().minusSeconds(1), assignee);

        workerWith(m -> new SendOutcome.TransientFailure("smtp refused")).pollOnce();

        assertThat(notifications())
                .as("the mail may still arrive — crying wolf on attempt one trains people to ignore this")
                .isEmpty();
    }

    @Test
    void aSuccessfulSendNotifiesNobody() {
        long assignee = insertUser("ravi", "DEVELOPER");
        insert("QUEUED", clock.instant().minusSeconds(1), assignee);

        workerWith(m -> new SendOutcome.Sent("provider-abc")).pollOnce();

        assertThat(notifications()).isEmpty();
    }

    /**
     * Pins the product rule to the shipped configuration rather than to a value
     * invented by the tests: §4B.6 says three retries, and max-attempts counts
     * the first send too.
     */
    @Test
    void theShippedConfigurationIsThreeRetries() {
        assertThat(shippedProperties.maxAttempts())
                .as("one initial attempt plus three retries")
                .isEqualTo(4);
    }

    // ------------------------------ D-034 · suppressed addresses are skipped

    /**
     * Suppression is only worth recording if something consults it. Every
     * avoidable send to a dead address costs sender reputation, which is what
     * decides whether the <em>next</em> escalation mail reaches anyone.
     */
    @Test
    void aSuppressedAddressIsNeverSentTo() {
        long assignee = insertUser("ravi", "DEVELOPER");
        long id = insert("QUEUED", clock.instant().minusSeconds(1), assignee);
        suppressions.suppress("dev@example.com",
                EmailSuppressions.SuppressionReason.BOUNCE, "550 mailbox unavailable", null);

        AtomicInteger sendsAttempted = new AtomicInteger();
        workerWith(m -> {
            sendsAttempted.incrementAndGet();
            return new SendOutcome.Sent("should-not-happen");
        }).pollOnce();

        assertThat(sendsAttempted).hasValue(0);
        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat((String) row.get("error_text")).contains("suppressed");
    }

    @Test
    void aSuppressedAddressStillNotifiesTheRecipientInApp() {
        long assignee = insertUser("ravi", "DEVELOPER");
        insert("QUEUED", clock.instant().minusSeconds(1), assignee);
        suppressions.suppress("dev@example.com",
                EmailSuppressions.SuppressionReason.COMPLAINT, "reported as spam", null);

        workerWith(m -> new SendOutcome.Sent("should-not-happen")).pollOnce();

        assertThat(notifications())
                .as("email is the one channel that does not work for them — the bell is all that is left")
                .singleElement()
                .extracting(row -> row.get("user_id"))
                .isEqualTo(assignee);
    }

    @Test
    void anUnsuppressedAddressIsUnaffected() {
        insertUser("ravi", "DEVELOPER");
        long id = insert("QUEUED", clock.instant().minusSeconds(1));
        suppressions.suppress("someone.else@example.com",
                EmailSuppressions.SuppressionReason.BOUNCE, null, null);

        workerWith(m -> new SendOutcome.Sent("provider-abc")).pollOnce();

        assertThat(row(id).get("status")).isEqualTo("SENT");
    }

    @Test
    void suppressionIsCaseInsensitiveAndReportsWhetherItWasNew() {
        assertThat(suppressions.suppress("Ravi@Example.com",
                EmailSuppressions.SuppressionReason.BOUNCE, "hard bounce", "msg-1"))
                .as("first suppression of this address")
                .isTrue();

        assertThat(suppressions.suppress("ravi@example.com",
                EmailSuppressions.SuppressionReason.BOUNCE, "hard bounce again", "msg-2"))
                .as("a replayed bounce must not re-alert the Admin")
                .isFalse();

        assertThat(suppressions.isSuppressed("RAVI@EXAMPLE.COM")).isTrue();
        assertThat(suppressions.isSuppressed("other@example.com")).isFalse();
    }

    // ------------------------------------------ D-035 · per-recipient limit

    /**
     * Blueprint §4B.6: a ticket edited five times in a minute must not put five
     * mails in the assignee's inbox — the assignee who gets that stops reading
     * any of them.
     */
    @Test
    void aSecondMailToTheSameRecipientAndTicketWithinTheWindowIsDropped() {
        long ticket = insertTicket("CRM-26-00001");

        assertThat(enqueueFor(ticket, "ravi@example.com")).isPresent();
        assertThat(enqueueFor(ticket, "ravi@example.com"))
                .as("the burst is throttled, not merely delayed")
                .isEmpty();

        assertThat(count()).isEqualTo(1);
    }

    @Test
    void theSameRecipientOnADifferentTicketIsNotThrottled() {
        long first = insertTicket("CRM-26-00001");
        long second = insertTicket("CRM-26-00002");

        assertThat(enqueueFor(first, "ravi@example.com")).isPresent();
        assertThat(enqueueFor(second, "ravi@example.com"))
                .as("a different ticket is a different conversation")
                .isPresent();
    }

    @Test
    void aDifferentRecipientOnTheSameTicketIsNotThrottled() {
        long ticket = insertTicket("CRM-26-00001");

        assertThat(enqueueFor(ticket, "ravi@example.com")).isPresent();
        assertThat(enqueueFor(ticket, "asha@example.com")).isPresent();
    }

    @Test
    void theLimitLapsesOnceTheWindowHasPassed() {
        long ticket = insertTicket("CRM-26-00001");

        assertThat(enqueueFor(ticket, "ravi@example.com")).isPresent();
        // Backdate rather than sleep for a minute.
        jdbc.update("UPDATE email_log SET queued_at = queued_at - INTERVAL 2 MINUTE");

        assertThat(enqueueFor(ticket, "ravi@example.com"))
                .as("a genuinely later update must still reach them")
                .isPresent();
    }

    /**
     * Non-ticket mail has {@code ticket_id IS NULL}, and plain {@code =} never
     * matches NULL to NULL — without NULL-safe equality every system mail would
     * skip the limit entirely.
     */
    @Test
    void systemMailWithNoTicketIsStillThrottledPerRecipient() {
        assertThat(enqueueFor(null, "ravi@example.com")).isPresent();
        assertThat(enqueueFor(null, "ravi@example.com")).isEmpty();
        assertThat(enqueueFor(null, "asha@example.com")).isPresent();
    }

    // -------------------------------------------------------------- enqueue

    /**
     * The reason the outbox exists (PLAN.md §2.2): a business transaction that
     * rolls back cannot leave a phantom mail queued.
     */
    @Test
    void enqueueRollsBackWithTheBusinessTransaction() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            enqueuer.enqueue(new NewMail(null, "TICKET_ASSIGNED", null, null,
                    "dev@example.com", "[CRM-26-00001] Assigned to you"));
            throw new IllegalStateException("business rule rejected the handoff");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count()).isZero();
    }

    @Test
    void enqueueCommitsWithTheBusinessTransactionAndIsImmediatelyClaimable() {
        transactions.executeWithoutResult(status ->
                enqueuer.enqueue(new NewMail(null, "TICKET_ASSIGNED", null, null,
                        "dev@example.com", "[CRM-26-00001] Assigned to you")));

        assertThat(count()).isEqualTo(1);
        // next_attempt_at defaults to now, so it is due on the very next poll.
        List<OutboxMessage> claimed =
                repository.claimBatch(10, Duration.ofMinutes(2), Instant.now().plusSeconds(1));
        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().toEmail()).isEqualTo("dev@example.com");
        assertThat(claimed.getFirst().retryCount()).isZero();
    }

    // --------------------------------------------------------------- helpers

    private OutboxWorker workerWith(MailTransport transport) {
        OutboxProperties properties = new OutboxProperties(
                true, Duration.ofSeconds(5), 10, Duration.ofMinutes(2),
                3, Duration.ofMinutes(1), Duration.ofHours(1), "test");
        return new OutboxWorker(
                repository, transport, properties, failureNotifier, suppressions, clock);
    }

    private long insert(String status, Instant nextAttemptAt) {
        return insert(status, nextAttemptAt, null);
    }

    private long insert(String status, Instant nextAttemptAt, Long toUserId) {
        jdbc.update("""
                INSERT INTO email_log (event_code, to_email, subject, status,
                                       retry_count, next_attempt_at, to_user_id)
                VALUES ('TICKET_ASSIGNED', 'dev@example.com', 'subject', ?, 0, ?, ?)
                """, status, java.sql.Timestamp.from(nextAttemptAt), toUserId);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }

    private OptionalLong enqueueFor(Long ticketId, String toEmail) {
        return transactions.execute(status -> enqueuer.enqueue(
                new NewMail(ticketId, "TICKET_ASSIGNED", null, null, toEmail, "subject")));
    }

    /** email_log.ticket_id is a real foreign key, so the rate-limit tests
     *  need real tickets rather than invented ids. */
    private long insertTicket(String ticketCode) {
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, level, original_level)
                VALUES (?, ?, 'rate limit fixture', 'MEDIUM', 'MEDIUM')
                """, ticketCode, fixtureProjectId());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }

    private long fixtureProjectId() {
        List<Long> existing =
                jdbc.queryForList("SELECT id FROM projects WHERE project_code = 'RL'", Long.class);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        jdbc.update("INSERT INTO projects (project_code, name) VALUES ('RL', 'Rate limit fixture')");
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }

    /** Roles are seeded by migration; users are not, so tests make their own. */
    private long insertUser(String username, String roleCode) {
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM roles WHERE code = ?", Long.class, roleCode);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?)
                """, username, username, username + "@example.com", username, roleId);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }

    private List<Map<String, Object>> notifications() {
        return jdbc.queryForList("SELECT * FROM notifications ORDER BY id");
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM email_log WHERE id = ?", id);
    }

    private Instant nextAttempt(long id) {
        java.sql.Timestamp ts = jdbc.queryForObject(
                "SELECT next_attempt_at FROM email_log WHERE id = ?", java.sql.Timestamp.class, id);
        return ts == null ? null : ts.toInstant();
    }

    private int count() {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM email_log", Integer.class);
        return c == null ? 0 : c;
    }

    /** A clock the test moves by hand, so backoff windows need no sleeping. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
