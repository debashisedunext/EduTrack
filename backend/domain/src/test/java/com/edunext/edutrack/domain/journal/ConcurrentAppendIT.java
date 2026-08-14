package com.edunext.edutrack.domain.journal;

import com.edunext.edutrack.domain.tickets.TicketHistory;
import com.edunext.edutrack.domain.tickets.TicketHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-045 · the claim PLAN.md §3.7 is built on, and the only test that runs two
 * appends at once.
 *
 * <p>Everything in {@code TicketJournalIT} appends <b>in sequence</b>, inside a
 * single transaction on a single thread — which is precisely the condition
 * under which a broken lock still looks perfect. §3.7's whole argument is that
 * two concurrent appends both read the same chain tail and produce a
 * <b>fork</b>: two rows carrying the same {@code prev_hash}, each internally
 * consistent, with A-044 reporting our own race as tampering months later. Up
 * to this test, nothing had ever put two writers against one ticket.
 *
 * <h2>Why this is its own class</h2>
 *
 * <p>{@code TicketJournalIT} is {@code @DataJpaTest}, so every test runs in a
 * transaction that is rolled back. Nothing written there is visible to another
 * connection, which makes concurrency untestable in it: a second thread would
 * see no ticket to append to. Every test here is
 * {@link Propagation#NOT_SUPPORTED} and commits, so the rows are real and the
 * container carries them between tests — hence one ticket per test rather than
 * a shared fixture.
 *
 * <h2>What "a single unbroken chain" is asserted to mean</h2>
 *
 * <p>Counting rows proves nothing, and neither does "every row has a hash". A
 * fork is <em>locally</em> perfect — both sides verify against their shared
 * parent. So the assertions are structural: exactly one row begins the chain,
 * no two rows claim the same parent, and walking from the genesis reaches every
 * row exactly once. The middle one is the fork detector; the last is what
 * proves nothing was orphaned.
 *
 * <h2>The control test is not decoration</h2>
 *
 * <p>A concurrency test that passes because the race never happened is worse
 * than no test — it reports a guarantee it never exercised, on every build,
 * for ever. {@link #theHarnessSeesAForkWhenTheLockIsBypassed} therefore stages
 * the race deliberately, with a barrier that holds both writers after they have
 * read the tail and before either inserts, and asserts the fork detector fires.
 * Only once that is known to work does the locked result mean anything.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TicketJournal.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConcurrentAppendIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_concurrency_it")
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
        // Every writer holds a connection for as long as it holds the lock, so
        // the pool has to outnumber them — otherwise the threads queue on
        // Hikari rather than on the row, and the test proves the pool works.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> WRITERS + 4);
    }

    /** Enough writers that a missing lock forks reliably, few enough to stay in one pool. */
    private static final int WRITERS = 8;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    DataSource dataSource;

    @Autowired
    TicketJournal journal;

    @Autowired
    TicketHistoryRepository history;

    @Autowired
    PlatformTransactionManager txManager;

    JdbcClient db;
    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(txManager);
    }

    // ------------------------------------------------------------------
    // The claim
    // ------------------------------------------------------------------

    @Test
    @DisplayName("N parallel appends to one ticket produce a single unbroken chain")
    void parallelAppendsDoNotForkTheChain() throws Exception {
        long ticketId = seedTicket();
        long userId = seedUser();

        runConcurrently(WRITERS, i -> tx.executeWithoutResult(status -> {
            TicketHistory entry = new TicketHistory();
            entry.setTicketId(ticketId);
            entry.setCycleNo((short) 1);
            entry.setEventType("STATUS_CHANGED");
            entry.setFieldName("status");
            entry.setNewValue("STEP_" + i);
            entry.setActorId(userId);
            entry.setActorType("USER");
            journal.append(entry);
        }));

        assertOneUnbrokenChain(ticketId, WRITERS);
    }

    /**
     * The same race with the writers <em>interleaved deliberately</em>: each
     * reads the tail, then waits until every other has read it too, and only
     * then appends. Without a lock this is the guaranteed fork; with one, the
     * first writer through holds the ticket row and the others block until it
     * commits, so their stale read is discarded and re-taken.
     *
     * <p>The plain version above relies on threads happening to overlap.
     * This one removes the luck.
     */
    @Test
    @DisplayName("the lock holds even when every writer has read the tail first")
    void theLockSerialisesWritersThatAllReadTheTailFirst() throws Exception {
        long ticketId = seedTicket();
        long userId = seedUser();
        CyclicBarrier everyoneHasRead = new CyclicBarrier(WRITERS);

        runConcurrently(WRITERS, i -> tx.executeWithoutResult(status -> {
            // An ordinary read — a timeline being rendered, a validation, a
            // ticket being loaded. It takes no locks and looks entirely
            // harmless, and it is what pins this transaction's REPEATABLE READ
            // snapshot. Deliberately NOT the tail method, which is a locking
            // read and would invert the lock order (ticket_history before
            // tickets) into a deadlock that says nothing about the chain.
            history.findByTicketIdOrderByIdAsc(ticketId);
            await(everyoneHasRead);

            TicketHistory entry = new TicketHistory();
            entry.setTicketId(ticketId);
            entry.setCycleNo((short) 1);
            entry.setEventType("ASSIGNED");
            entry.setNewValue("STEP_" + i);
            entry.setActorId(userId);
            entry.setActorType("USER");
            journal.append(entry);
        }));

        assertOneUnbrokenChain(ticketId, WRITERS);
    }

    // ------------------------------------------------------------------
    // The control — proving the assertions above can fail
    // ------------------------------------------------------------------

    /**
     * <b>Not a test of production code.</b> It stages the fork §3.7 describes by
     * writing rows the journal would refuse — caller-computed {@code prev_hash},
     * inserted through the repository with no lock taken — and asserts that
     * {@link #forkedParents} sees it.
     *
     * <p>Without this, {@link #parallelAppendsDoNotForkTheChain} would pass just
     * as happily against a fork detector that never fires, and nobody would
     * know until A-044 started reporting breaks in production. The repository is
     * reachable here because A-037's rule is checked against production classes
     * only; this is a test staging a defect on purpose, which is the one
     * legitimate use for it.
     */
    @Test
    @DisplayName("the harness sees a fork when the lock is bypassed")
    void theHarnessSeesAForkWhenTheLockIsBypassed() throws Exception {
        long ticketId = seedTicket();
        long userId = seedUser();
        // one row, so there is a tail for both writers to claim as parent
        tx.executeWithoutResult(status -> journal.append(genesisFor(ticketId, userId)));

        String sharedTail = db.sql("SELECT row_hash FROM ticket_history WHERE ticket_id = ? "
                        + "ORDER BY id DESC LIMIT 1")
                .param(ticketId)
                .query(String.class)
                .single();

        CyclicBarrier bothHaveRead = new CyclicBarrier(2);
        runConcurrently(2, i -> tx.executeWithoutResult(status -> {
            await(bothHaveRead);
            db.sql("INSERT INTO ticket_history (ticket_id, cycle_no, event_type, actor_id, "
                            + "actor_type, prev_hash, row_hash) VALUES (?, 1, 'FORKED', ?, 'USER', ?, ?)")
                    .params(ticketId, userId, sharedTail, "f".repeat(63) + i)
                    .update();
        }));

        assertThat(forkedParents(ticketId))
                .as("two rows share a prev_hash — this is what a fork looks like, and the "
                        + "assertions in the other tests are worth nothing unless this is seen")
                .isNotEmpty();
    }

    // ------------------------------------------------------------------
    // Chain assertions
    // ------------------------------------------------------------------

    /**
     * Structural, not arithmetic. A fork verifies perfectly on both sides, so
     * "every row hashes correctly" cannot detect one — what distinguishes a
     * chain from a fork is that no parent is claimed twice and everything is
     * reachable from a single start.
     */
    private void assertOneUnbrokenChain(long ticketId, int expectedRows) {
        List<Map<String, Object>> rows = db.sql(
                        "SELECT id, prev_hash, row_hash FROM ticket_history "
                                + "WHERE ticket_id = ? ORDER BY id")
                .param(ticketId)
                .query()
                .listOfRows();

        assertThat(rows).as("every writer committed exactly one row").hasSize(expectedRows);

        assertThat(rows).filteredOn(r -> r.get("prev_hash") == null)
                .as("exactly one row begins the chain — a second genesis is a fork that "
                        + "verifies from that point on while orphaning everything before it")
                .hasSize(1);

        assertThat(forkedParents(ticketId))
                .as("no two rows may claim the same parent — this is the fork §3.7 exists to "
                        + "prevent, and the control test proves this check can fire")
                .isEmpty();

        assertThat(rows).extracting(r -> r.get("row_hash"))
                .as("every link is distinct")
                .doesNotHaveDuplicates();

        // Walk it: genesis, then whoever names that row as parent, N times over.
        Map<String, String> childOf = new HashMap<>();
        String genesis = null;
        for (Map<String, Object> row : rows) {
            String prev = (String) row.get("prev_hash");
            String self = (String) row.get("row_hash");
            if (prev == null) {
                genesis = self;
            } else {
                childOf.put(prev, self);
            }
        }
        Set<String> visited = new HashSet<>();
        String cursor = genesis;
        while (cursor != null && visited.add(cursor)) {
            cursor = childOf.get(cursor);
        }
        assertThat(visited)
                .as("walking from the genesis has to reach every row — a chain that is fork-free "
                        + "can still have left rows stranded off the end")
                .hasSize(expectedRows);
    }

    /** Every {@code prev_hash} claimed by more than one row. Empty means no fork. */
    private List<String> forkedParents(long ticketId) {
        return db.sql("SELECT prev_hash FROM ticket_history WHERE ticket_id = ? "
                        + "AND prev_hash IS NOT NULL GROUP BY prev_hash HAVING COUNT(*) > 1")
                .param(ticketId)
                .query(String.class)
                .list();
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    /**
     * Runs {@code n} bodies at once and rethrows whatever any of them threw.
     *
     * <p>Every failure is surfaced: a writer that dies on a deadlock or a lock
     * timeout is exactly the result this test exists to report, and swallowing
     * it would leave a green build with fewer rows than writers — which the row
     * count would then catch, but as "N-1 rows" rather than as the deadlock it
     * actually was.
     */
    private void runConcurrently(int n, ThrowingIntConsumer body) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CyclicBarrier allReady = new CyclicBarrier(n);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    await(allReady);
                    body.accept(index);
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @FunctionalInterface
    private interface ThrowingIntConsumer {
        void accept(int index) throws Exception;
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("writers did not meet at the barrier", e);
        }
    }

    private TicketHistory genesisFor(long ticketId, long userId) {
        TicketHistory entry = new TicketHistory();
        entry.setTicketId(ticketId);
        entry.setCycleNo((short) 1);
        entry.setEventType("CREATED");
        entry.setActorId(userId);
        entry.setActorType("USER");
        return entry;
    }

    // ------------------------------------------------------------------
    // Seed — committed, because another connection has to see it
    // ------------------------------------------------------------------

    private long seedTicket() {
        Long roleId = db.sql("SELECT id FROM roles WHERE code = 'DEVELOPER'").query(Long.class).single();
        int n = SEQ.incrementAndGet();
        db.sql("INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id) "
                        + "VALUES (?, ?, ?, 'x', 'Concurrency IT', ?)")
                .params("E-C-" + n, "concurrency.it." + n, "concurrency.it." + n + "@example.com", roleId)
                .update();
        Long projectId = db.sql("SELECT id FROM projects ORDER BY id LIMIT 1").query(Long.class).optional()
                .orElseGet(() -> {
                    db.sql("INSERT INTO projects (project_code, name) VALUES (?, 'Concurrency IT Project')")
                            .param("CIT" + n)
                            .update();
                    return db.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
                });
        db.sql("INSERT INTO tickets (ticket_code, project_id, title, level, original_level) "
                        + "VALUES (?, ?, 'concurrency probe', 'LOW', 'LOW')")
                .params("CIT-26-" + n, projectId)
                .update();
        return db.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    private long seedUser() {
        return db.sql("SELECT id FROM users ORDER BY id DESC LIMIT 1").query(Long.class).single();
    }
}
