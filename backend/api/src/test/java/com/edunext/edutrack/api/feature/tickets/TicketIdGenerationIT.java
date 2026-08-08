package com.edunext.edutrack.api.feature.tickets;

import com.edunext.edutrack.domain.identity.Project;
import com.edunext.edutrack.domain.identity.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * C-011 · ticket-ID generation against a real MySQL, a real Flyway run and real
 * concurrent connections.
 *
 * <p>{@link TicketCodeTest} and {@link TicketCodeGeneratorTest} prove the logic.
 * They cannot prove the only thing this task is actually about, because
 * <b>the failure mode of a wrong implementation is invisible without
 * concurrency</b>. {@code SELECT COUNT(*) + 1} passes every single-threaded test
 * anyone would think to write and mints duplicate IDs the first time two people
 * click Save in the same second. So the load-bearing test here is
 * {@link #concurrentAllocationsAreAllDistinct()}; the rest guard the conditions
 * that make it true.
 */
@SpringBootTest
@Testcontainers
class TicketIdGenerationIT {

    /** Distinct project code per test, so no test can see another's counter. */
    private static final AtomicInteger PROJECT_SEQ = new AtomicInteger();

    /**
     * Distinct per <i>run</i> as well as per test.
     *
     * <p>The container is thrown away each run, so a plain counter would do here
     * — but only here. Point this suite at a persistent database (a developer's
     * local stack, a shared CI schema) and every fixture code collides with the
     * previous run's on {@code uq_projects_code}, and the whole class errors out
     * in the fixture with a duplicate-key message that says nothing about ticket
     * IDs. Four characters of entropy costs nothing and keeps the failure honest.
     */
    private static final String RUN_ID =
            UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase(Locale.ROOT);

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

    /** Flyway is redirected too — application.yml migrates as {@code edutrack_migrate} (A-010). */
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
    TicketCodeGenerator generator;

    @Autowired
    TicketSequenceRepository sequences;

    /** B-005's JPA entity — present here to reproduce the flush hazard, not to use it. */
    @Autowired
    ProjectRepository projects;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    private long projectId;
    private String projectCode;

    @BeforeEach
    void createProject() {
        // "IT" + run id + counter — eight characters, inside the contract's
        // ^[A-Z][A-Z0-9]{1,9}$ and clear of anything Stream B's seed migrations claim.
        projectCode = "IT%s%02d".formatted(RUN_ID, PROJECT_SEQ.incrementAndGet());
        jdbc.update("INSERT INTO projects (project_code, name) VALUES (?, ?)",
                projectCode, "C-011 fixture " + projectCode);
        projectId = jdbc.queryForObject(
                "SELECT id FROM projects WHERE project_code = ?", Long.class, projectCode);
    }

    /** Every call must be inside a transaction — that is the point of {@code MANDATORY}. */
    private String nextCode() {
        return tx.execute(status -> generator.nextTicketCode(projectId));
    }

    // ── the shape of what is issued ─────────────────────────────────────────

    @Test
    @DisplayName("a brand-new project issues 00001, then 00002 — the counter starts at zero")
    void firstTicketIsNumberOne() {
        int year = LocalDate.now(ZoneOffset.UTC).getYear() % 100;

        assertThat(nextCode()).isEqualTo("%s-%02d-00001".formatted(projectCode, year));
        assertThat(nextCode()).isEqualTo("%s-%02d-00002".formatted(projectCode, year));
    }

    @Test
    @DisplayName("the counter is per project — two projects both start at 00001")
    void countersAreIndependentPerProject() {
        String first = nextCode();

        createProject(); // second fixture project, own code and own counter
        String second = nextCode();

        assertThat(first).endsWith("-00001");
        assertThat(second).endsWith("-00001");
        assertThat(first).isNotEqualTo(second);
    }

    // ── the reason this task exists ─────────────────────────────────────────

    @Test
    @DisplayName("64 concurrent allocations on one project produce 64 distinct, gapless numbers")
    void concurrentAllocationsAreAllDistinct() throws Exception {
        int threads = 8;
        int perThread = 8;
        int expected = threads * perThread;

        // Every worker is released at once, so the allocations genuinely overlap
        // rather than queueing behind thread startup. This is the arrangement in
        // which COUNT(*) fails and LAST_INSERT_ID(expr) does not.
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<List<String>>> workers = IntStream.range(0, threads)
                    .mapToObj(t -> (Callable<List<String>>) () -> {
                        start.await();
                        return IntStream.range(0, perThread)
                                .mapToObj(i -> nextCode())
                                .toList();
                    })
                    .toList();

            List<Future<List<String>>> futures = workers.stream().map(pool::submit).toList();
            start.countDown();

            Set<String> issued = new HashSet<>();
            for (Future<List<String>> future : futures) {
                issued.addAll(future.get(60, TimeUnit.SECONDS));
            }

            // Distinct: no two callers were handed the same ticket ID. The
            // uq_tickets_code index would eventually catch a duplicate, but as a
            // 500 on someone's ticket, not as a test failure.
            assertThat(issued).hasSize(expected);

            // Gapless: nothing was skipped either, so the counter moved exactly
            // once per call rather than racing forward.
            Long finalSeq = jdbc.queryForObject(
                    "SELECT ticket_seq FROM projects WHERE id = ?", Long.class, projectId);
            assertThat(finalSeq).isEqualTo(expected);
        } finally {
            pool.shutdownNow();
        }
    }

    // ── the transaction that makes it work ──────────────────────────────────

    @Test
    @DisplayName("outside a transaction it refuses to run at all")
    void refusesToAllocateWithoutATransaction() {
        // Not pedantry. LAST_INSERT_ID() is connection-local: without a
        // transaction binding one connection to the call, the UPDATE and the
        // SELECT can land on different pooled connections and the SELECT answers
        // with whatever the other connection last did. MANDATORY turns that into
        // a startup-loud failure instead of a rare duplicate ID.
        assertThatExceptionOfType(IllegalTransactionStateException.class)
                .isThrownBy(() -> generator.nextTicketCode(projectId));

        Long seq = jdbc.queryForObject(
                "SELECT ticket_seq FROM projects WHERE id = ?", Long.class, projectId);
        assertThat(seq).isZero();
    }

    @Test
    @DisplayName("a rolled-back creation gives the number back")
    void rollbackReleasesTheSequence() {
        String first = nextCode();

        tx.execute(status -> {
            String discarded = generator.nextTicketCode(projectId);
            assertThat(discarded).endsWith("-00002");
            status.setRollbackOnly();
            return null;
        });

        assertThat(first).endsWith("-00001");
        // Reused, not skipped: the increment is part of the caller's transaction,
        // so a failed ticket insert does not leave a hole. Gaps would be harmless
        // — uniqueness is the guarantee, not density — but reuse is free here.
        assertThat(nextCode()).endsWith("-00002");
    }

    // ── the JPA trap B-005 created ──────────────────────────────────────────

    @Test
    @DisplayName("a dirty managed Project in the same transaction cannot revert the allocation")
    void jpaFlushCannotRevertTheAllocation() {
        // This is C-010's transaction, almost exactly: load the project, touch
        // something on it, allocate the ticket code, commit. It is the natural
        // way to write that method, and before `updatable = false` it silently
        // undid the allocation — Hibernate flushes the whole dirty row at commit,
        // including the ticket_seq it read *before* the raw increment ran.
        //
        // Nothing about it looks wrong. No exception, no warning, and the ticket
        // is created fine. The damage lands on the *next* ticket, which reuses
        // the number and dies on uq_tickets_code — in a different request, on a
        // different day, with no trace back to here.
        tx.execute(status -> {
            Project project = projects.findById(projectId).orElseThrow();
            project.setName("renamed inside the creation transaction");

            String code = generator.nextTicketCode(projectId);
            assertThat(code).endsWith("-00001");
            return null;
        });

        Long seq = jdbc.queryForObject(
                "SELECT ticket_seq FROM projects WHERE id = ?", Long.class, projectId);
        assertThat(seq)
                .as("Hibernate flushed the dirty Project and wrote ticket_seq back to its stale value")
                .isEqualTo(1L);

        // And the proof it is not merely a lucky ordering: the very next
        // allocation must be 2, not a second 1.
        assertThat(nextCode()).endsWith("-00002");
    }

    // ── the zero-row trap ───────────────────────────────────────────────────

    @Test
    @DisplayName("an unknown project id is rejected rather than served another project's number")
    void unknownProjectIsRejected() {
        long absent = 9_000_000L;

        assertThatExceptionOfType(UnknownProjectException.class)
                .isThrownBy(() -> tx.execute(status -> generator.nextTicketCode(absent)));
    }

    @Test
    @DisplayName("allocating for a missing project after a real one does not reissue its number")
    void zeroRowUpdateDoesNotEchoTheLastAllocation() {
        // The specific hazard: an UPDATE matching no rows leaves LAST_INSERT_ID()
        // holding the value this connection set a moment ago. Without the
        // row-count guard, the second call below would happily return the first
        // call's number for a project that does not exist — and, with connection
        // reuse, for one that does.
        tx.execute(status -> {
            String real = generator.nextTicketCode(projectId);
            assertThat(real).endsWith("-00001");

            assertThatExceptionOfType(UnknownProjectException.class)
                    .isThrownBy(() -> sequences.allocateNextSequence(9_000_001L));
            return null;
        });
    }
}
