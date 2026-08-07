package com.edunext.edutrack.worker.outbox;

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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        // The worker leaves migration to api in production; the test has no
        // api, so it builds the schema itself from the same migrations.
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("edutrack.outbox.enabled", () -> false);
    }

    @Autowired
    OutboxRepository repository;

    @Autowired
    OutboxEnqueuer enqueuer;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    private MutableClock clock;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM email_log");
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
        return new OutboxWorker(repository, transport, properties, clock);
    }

    private long insert(String status, Instant nextAttemptAt) {
        jdbc.update("""
                INSERT INTO email_log (event_code, to_email, subject, status,
                                       retry_count, next_attempt_at)
                VALUES ('TICKET_ASSIGNED', 'dev@example.com', 'subject', ?, 0, ?)
                """, status, java.sql.Timestamp.from(nextAttemptAt));
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
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
