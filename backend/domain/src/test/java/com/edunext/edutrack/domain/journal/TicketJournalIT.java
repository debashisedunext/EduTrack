package com.edunext.edutrack.domain.journal;

import com.edunext.edutrack.domain.tickets.TicketEffortLog;
import com.edunext.edutrack.domain.tickets.TicketEffortLogRepository;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import com.edunext.edutrack.domain.tickets.TicketHistoryRepository;
import com.edunext.edutrack.domain.workflow.TicketStageTransition;
import com.edunext.edutrack.domain.workflow.TicketStageTransitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A-040 · the journal against real MySQL 8.4, for the assertions a mock cannot
 * make.
 *
 * <p>{@code TicketJournalTest} covers every validation cheaply and on every
 * build. Four things are left over, and each of them is a claim about
 * infrastructure rather than about this class's logic:
 *
 * <ul>
 *   <li>{@code MANDATORY} really refuses a caller with no transaction. Mocked,
 *       the annotation is inert — the proxy that enforces it does not exist —
 *       so the guarantee that a handoff's three appends share one lock is
 *       untested until a container runs it.</li>
 *   <li>The one-open-hop invariant resolves through A-009's
 *       {@code current_ticket_id} <b>stored generated column</b>, which only
 *       MySQL computes. The unit test stubs {@code findByCurrentTicketId} and
 *       therefore proves the branch, not the mechanism.</li>
 *   <li>{@code seal} lands on the row, past the {@code @Immutable} entity and
 *       the {@code trg_stage_seal_only} trigger, changing the three columns it
 *       is allowed to and no others.</li>
 *   <li>A correction row satisfies {@code fk_history_corrects}, so the pair the
 *       journal insists on is one the schema can actually hold.</li>
 * </ul>
 *
 * <p><b>Not here:</b> that the A-008 triggers reject UPDATE and DELETE.
 * {@code SchemaIntegrationIT} is A-013 and proves that against raw JDBC, which
 * is the right level for it — a second copy would drift.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TicketJournal.class)
class TicketJournalIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_journal_it")
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

    /**
     * Ticket codes are unique and one test runs without a transaction to roll
     * back, so they cannot be a constant.
     */
    private static final AtomicInteger SEQ = new AtomicInteger();

    private static final Instant ENTERED = Instant.parse("2026-08-14T09:00:00Z");

    @Autowired
    DataSource dataSource;

    @Autowired
    TicketJournal journal;

    @Autowired
    TicketHistoryRepository history;

    @Autowired
    TicketEffortLogRepository effortLogs;

    @Autowired
    TicketStageTransitionRepository transitions;

    /**
     * A-042's round-trip tests need the row to come back from MySQL rather than
     * from the persistence context — an entity still in the first-level cache is
     * the same object that was hashed, so it would prove nothing about what was
     * actually stored.
     */
    @PersistenceContext
    EntityManager entityManager;

    JdbcClient db;

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(dataSource);
    }

    /** Seeded per test rather than shared — every one of these has a real foreign key. */
    private long seedTicket() {
        Long roleId = db.sql("SELECT id FROM roles WHERE code = 'DEVELOPER'").query(Long.class).single();
        int n = SEQ.incrementAndGet();
        db.sql("INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id) "
                        + "VALUES (?, ?, ?, 'x', 'Journal IT', ?)")
                .params("E-J-" + n, "journal.it." + n, "journal.it." + n + "@example.com", roleId)
                .update();
        Long projectId = db.sql("SELECT id FROM projects ORDER BY id LIMIT 1").query(Long.class).optional()
                .orElseGet(() -> {
                    db.sql("INSERT INTO projects (project_code, name) VALUES (?, 'Journal IT Project')")
                            .param("JIT" + n)
                            .update();
                    return db.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
                });
        db.sql("INSERT INTO tickets (ticket_code, project_id, title, level, original_level) "
                        + "VALUES (?, ?, 'journal probe', 'LOW', 'LOW')")
                .params("JIT-26-" + n, projectId)
                .update();
        return db.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    private long seedUser() {
        return db.sql("SELECT id FROM users ORDER BY id DESC LIMIT 1").query(Long.class).single();
    }

    private TicketStageTransition openHop(long ticketId, int seqNo, String toStage) {
        TicketStageTransition hop = new TicketStageTransition();
        hop.setTicketId(ticketId);
        hop.setCycleNo((short) 1);
        hop.setSeqNo(seqNo);
        hop.setToStage(toStage);
        hop.setActionCode("FORWARD");
        hop.setEnteredAt(ENTERED);
        return hop;
    }

    // ------------------------------------------------------------------
    // The transaction the lock needs
    // ------------------------------------------------------------------

    /**
     * The whole point of {@code MANDATORY}, and the only place it can be shown.
     *
     * <p>With the default {@code REQUIRED} this call would succeed: Spring would
     * open a transaction, take {@code SELECT … FOR UPDATE}, insert, commit, and
     * release the lock before the caller's next append — so a handoff writing
     * history, effort and a transition would take three separate locks with room
     * for a concurrent handoff between them. Nothing would fail; the ribbon
     * would simply grow a second open hop, or the chain would fork, on some
     * fraction of concurrent writes.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void refusesToAppendOutsideTheCallersTransaction() {
        TicketHistory entry = new TicketHistory();
        entry.setTicketId(1L);
        entry.setEventType("CREATED");
        entry.setActorType("SYSTEM");

        assertThatThrownBy(() -> journal.append(entry))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("mandatory");
    }

    // ------------------------------------------------------------------
    // Appends against the real schema
    // ------------------------------------------------------------------

    @Test
    void everyAppendLandsAndReadsBack() {
        long ticketId = seedTicket();
        long userId = seedUser();

        TicketHistory entry = new TicketHistory();
        entry.setTicketId(ticketId);
        entry.setCycleNo((short) 1);
        entry.setEventType("CREATED");
        entry.setActorId(userId);
        entry.setActorType("USER");
        journal.append(entry);

        TicketEffortLog log = new TicketEffortLog();
        log.setTicketId(ticketId);
        log.setCycleNo((short) 1);
        log.setStageCode("DEV");
        log.setIterationNo((short) 1);
        log.setUserId(userId);
        log.setWorkDate(LocalDate.of(2026, 8, 14));
        log.setHours(new BigDecimal("3.50"));
        journal.append(log);

        journal.append(openHop(ticketId, 1, "DEV"));

        assertThat(history.findByTicketIdOrderByIdAsc(ticketId))
                .singleElement()
                .satisfies(h -> {
                    assertThat(h.getId()).isNotNull();
                    assertThat(h.getEventType()).isEqualTo("CREATED");
                    // A-042 fills these. The first row of a chain has no
                    // predecessor, so prev_hash is null and row_hash is not —
                    // an unchained row is the one with both null.
                    assertThat(h.getPrevHash()).isNull();
                    assertThat(h.getRowHash()).hasSize(ChainDigest.HASH_LENGTH);
                });
        assertThat(effortLogs.findByTicketIdOrderByIdAsc(ticketId)).hasSize(1);
        assertThat(transitions.findByTicketIdOrderBySeqNoAsc(ticketId)).hasSize(1);
    }

    /**
     * The correction pair the journal insists on, proved to be one the schema can
     * hold: {@code fk_history_corrects} points back at {@code ticket_history.id},
     * so a reversal naming an entry that was never written cannot be stored.
     */
    @Test
    void aCorrectionPointsAtTheEntryItReverses() {
        long ticketId = seedTicket();
        long userId = seedUser();

        TicketHistory original = new TicketHistory();
        original.setTicketId(ticketId);
        original.setEventType("LEVEL_CHANGED");
        original.setFieldName("level");
        original.setOldValue("LOW");
        original.setNewValue("CRITICAL");
        original.setActorId(userId);
        original.setActorType("USER");
        journal.append(original);

        TicketHistory reversal = new TicketHistory();
        reversal.setTicketId(ticketId);
        reversal.setEventType("LEVEL_CHANGED");
        reversal.setFieldName("level");
        reversal.setOldValue("CRITICAL");
        reversal.setNewValue("LOW");
        reversal.setActorId(userId);
        reversal.setActorType("USER");
        reversal.setRemarks("Raised in error.");
        reversal.setCorrection(true);
        reversal.setCorrectsEntryId(original.getId());
        journal.append(reversal);

        assertThat(history.findByCorrectsEntryId(original.getId()))
                .singleElement()
                .satisfies(r -> assertThat(r.isCorrection()).isTrue());
        assertThat(history.findByTicketIdOrderByIdAsc(ticketId))
                .as("the original is never touched — it is still there, unchanged")
                .hasSize(2)
                .first()
                .satisfies(h -> assertThat(h.getNewValue()).isEqualTo("CRITICAL"));
    }

    // ------------------------------------------------------------------
    // The ribbon invariant, through MySQL's generated column
    // ------------------------------------------------------------------

    /**
     * {@code current_ticket_id} is {@code IF(is_current = 1, ticket_id, NULL)},
     * computed by MySQL and never written by us (A-009, PLAN.md §3.3). This is
     * the assertion the unit test cannot make, because there the column's value
     * is whatever the stub returns.
     */
    @Test
    void theOpenHopIsTheOneMySqlComputes() {
        long ticketId = seedTicket();

        TicketStageTransition hop = journal.append(openHop(ticketId, 1, "DEV"));

        assertThat(transitions.findByCurrentTicketId(ticketId))
                .get()
                .satisfies(open -> assertThat(open.getId()).isEqualTo(hop.getId()));
    }

    @Test
    void refusesASecondOpenHopOnTheSameTicket() {
        long ticketId = seedTicket();
        journal.append(openHop(ticketId, 1, "DEV"));

        assertThatThrownBy(() -> journal.append(openHop(ticketId, 2, "QA")))
                .isInstanceOf(AppendRejectedException.class)
                .hasMessageContaining("still in stage DEV");

        assertThat(transitions.findByTicketIdOrderBySeqNoAsc(ticketId)).hasSize(1);
    }

    @Test
    void sealThenAppendIsTheOrderThatWorks() {
        long ticketId = seedTicket();
        TicketStageTransition dev = journal.append(openHop(ticketId, 1, "DEV"));

        assertThat(journal.seal(dev.getId(), ENTERED.plus(4, ChronoUnit.HOURS), 240)).isTrue();
        journal.append(openHop(ticketId, 2, "QA"));

        assertThat(transitions.findByTicketIdOrderBySeqNoAsc(ticketId)).hasSize(2);
        assertThat(transitions.findByCurrentTicketId(ticketId))
                .get()
                .satisfies(open -> assertThat(open.getToStage()).isEqualTo("QA"));
    }

    // ------------------------------------------------------------------
    // Sealing
    // ------------------------------------------------------------------

    /**
     * Read back with raw SQL rather than through the repository: a
     * {@code @Modifying} query does not update the persistence context, so
     * asking Hibernate would report what it still believes. This asks the table.
     */
    @Test
    void sealSetsExactlyTheThreeColumnsTheTriggerAllows() {
        long ticketId = seedTicket();
        TicketStageTransition hop = journal.append(openHop(ticketId, 1, "DEV"));
        Instant exited = ENTERED.plus(4, ChronoUnit.HOURS);

        assertThat(journal.seal(hop.getId(), exited, 200)).isTrue();

        // LocalDateTime, not Timestamp: the column is DATETIME(6) holding UTC and
        // the driver hands a LocalDateTime back verbatim, where getTimestamp
        // would apply a timezone and make the assertion depend on where it runs.
        db.sql("SELECT exited_at, duration_mins, is_current, entered_at, to_stage "
                        + "FROM ticket_stage_transitions WHERE id = ?")
                .param(hop.getId())
                .query((rs, rowNum) -> {
                    assertThat(rs.getObject("exited_at", LocalDateTime.class))
                            .isEqualTo(LocalDateTime.ofInstant(exited, ZoneOffset.UTC));
                    assertThat(rs.getInt("duration_mins")).isEqualTo(200);
                    assertThat(rs.getBoolean("is_current")).isFalse();
                    assertThat(rs.getObject("entered_at", LocalDateTime.class))
                            .as("everything else is exactly as appended — trg_stage_seal_only "
                                    + "would have refused otherwise")
                            .isEqualTo(LocalDateTime.ofInstant(ENTERED, ZoneOffset.UTC));
                    assertThat(rs.getString("to_stage")).isEqualTo("DEV");
                    return rowNum;
                })
                .single();
    }

    @Test
    void aSecondSealIsANoOpRatherThanAnError() {
        long ticketId = seedTicket();
        TicketStageTransition hop = journal.append(openHop(ticketId, 1, "DEV"));
        Instant exited = ENTERED.plus(4, ChronoUnit.HOURS);

        assertThat(journal.seal(hop.getId(), exited, 200)).isTrue();
        assertThat(journal.seal(hop.getId(), exited.plus(1, ChronoUnit.HOURS), 260))
                .as("the exited_at is null predicate decides, so the second caller is told it lost")
                .isFalse();

        assertThat(db.sql("SELECT duration_mins FROM ticket_stage_transitions WHERE id = ?")
                .param(hop.getId())
                .query(Integer.class)
                .single())
                .as("the first seal's figure stands; the second changed nothing")
                .isEqualTo(200);
    }

    /**
     * The unit test proves the arithmetic. This proves it is reached before the
     * {@code @Modifying} query runs, so a caller that passes seconds does not
     * write them and then hear about it.
     */
    @Test
    void refusesAnImpossibleDurationBeforeTouchingTheRow() {
        long ticketId = seedTicket();
        TicketStageTransition hop = journal.append(openHop(ticketId, 1, "DEV"));

        assertThatThrownBy(() -> journal.seal(hop.getId(), ENTERED.plus(4, ChronoUnit.HOURS), 14_400))
                .isInstanceOf(AppendRejectedException.class);

        assertThat(db.sql("SELECT COUNT(*) FROM ticket_stage_transitions "
                        + "WHERE id = ? AND exited_at IS NULL AND is_current = 1")
                .param(hop.getId())
                .query(Integer.class)
                .single())
                .as("the hop is still open — the guard ran before the update, not after it")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // A-042 · the chain, against a real database
    // ------------------------------------------------------------------

    /**
     * <b>The claim A-044 depends on, and the only test that can make it.</b>
     *
     * <p>Every unit test hashes an object that is still in memory. What the
     * nightly verifier does instead is read a row back out of MySQL and
     * recompute — and MySQL does not hand back what was handed to it.
     * {@code DECIMAL(5,2)} returns {@code 3.50} for a value written as
     * {@code 3.5}; {@code DATETIME(6)} returns an {@code Instant} rebuilt from
     * six stored digits. If either round trip moved the payload, every row in
     * the corpus would fail verification on the first night and read as
     * tampering.
     *
     * <p>So this writes deliberately trap-shaped values, evicts them from the
     * persistence context, reads them back and recomputes with the same two
     * classes A-044 will use.
     */
    @Test
    void aRowReadBackFromMySqlStillReproducesItsOwnHash() {
        long ticketId = seedTicket();
        long userId = seedUser();

        TicketEffortLog log = new TicketEffortLog();
        log.setTicketId(ticketId);
        log.setCycleNo((short) 1);
        log.setStageCode("DEV");
        log.setIterationNo((short) 1);
        log.setUserId(userId);
        log.setWorkDate(LocalDate.of(2026, 8, 14));
        // Written with one decimal place, stored as DECIMAL(5,2), read back as
        // "3.50". stripTrailingZeros is the whole reason these are one value.
        log.setHours(new BigDecimal("3.5"));
        log.setNote("Chased the discount-code 500 — reproduced on staging");
        journal.append(log);

        // A whole second and a fractional one: the trimming-formatter trap in
        // both directions, through DATETIME(6) rather than through a formatter.
        TicketStageTransition hop = openHop(ticketId, 1, "DEV");
        hop.setEnteredAt(Instant.parse("2026-08-14T09:30:00.250000Z"));
        journal.append(hop);

        entityManager.flush();
        entityManager.clear();

        TicketEffortLog stored = effortLogs.findByTicketIdOrderByIdAsc(ticketId).getFirst();
        assertThat(ChainDigest.rowHash(stored.getPrevHash(), ChainPayloads.of(stored)))
                .as("an effort log recomputed from what MySQL returned must equal what was stored "
                        + "— this is exactly what A-044's verifier does, and a mismatch here is the "
                        + "false tamper alert the whole canonical-form exercise exists to prevent")
                .isEqualTo(stored.getRowHash());

        TicketStageTransition storedHop = transitions.findByTicketIdOrderBySeqNoAsc(ticketId).getFirst();
        assertThat(ChainDigest.rowHash(storedHop.getPrevHash(), ChainPayloads.of(storedHop)))
                .as("and a hop, whose entered_at made the round trip through DATETIME(6)")
                .isEqualTo(storedHop.getRowHash());
    }

    /**
     * The chain is per-ticket and per-table (D1). Three appends to one table
     * produce three links; the tail of one table is never the tail of another.
     */
    @Test
    void appendsToOneTableFormOneUnbrokenChain() {
        long ticketId = seedTicket();
        long userId = seedUser();

        for (int i = 0; i < 3; i++) {
            TicketHistory entry = new TicketHistory();
            entry.setTicketId(ticketId);
            entry.setCycleNo((short) 1);
            entry.setEventType("STATUS_CHANGED");
            entry.setFieldName("status");
            entry.setNewValue("STEP_" + i);
            entry.setActorId(userId);
            entry.setActorType("USER");
            journal.append(entry);
        }

        entityManager.flush();
        entityManager.clear();

        var chain = history.findByTicketIdOrderByIdAsc(ticketId);
        assertThat(chain).hasSize(3);
        assertThat(chain.getFirst().getPrevHash())
                .as("the genesis row begins the chain")
                .isNull();
        for (int i = 1; i < chain.size(); i++) {
            assertThat(chain.get(i).getPrevHash())
                    .as("row %d must point at row %d — a null here is a second genesis row, which "
                            + "is a fork that verifies perfectly and hides everything before it",
                            i, i - 1)
                    .isEqualTo(chain.get(i - 1).getRowHash());
        }
        assertThat(chain).extracting(TicketHistory::getRowHash).doesNotHaveDuplicates();
    }

    // ------------------------------------------------------------------
    // A-043 · compensating entries
    // ------------------------------------------------------------------

    /**
     * <b>The claim A-043 exists to make</b>, and the only test that can make it:
     * a reversal has to cancel in the query that actually renders the §4A.4
     * grid, not merely in the rows.
     *
     * <p>Asserting that two rows exist with opposite signs would pass against a
     * reversal filed in the wrong stage or iteration — the arithmetic the grid
     * does is a join, so the join is what has to be exercised. This runs
     * PLAN.md §3.4's roll-up verbatim.
     */
    @Test
    void aReversalNetsTheCellToZeroInTheRollUp() {
        long ticketId = seedTicket();
        long userId = seedUser();
        journal.append(openHop(ticketId, 1, "DEV"));

        TicketEffortLog logged = new TicketEffortLog();
        logged.setTicketId(ticketId);
        logged.setCycleNo((short) 1);
        logged.setStageCode("DEV");
        logged.setIterationNo((short) 1);
        logged.setUserId(userId);
        logged.setWorkDate(LocalDate.of(2026, 8, 14));
        logged.setHours(new BigDecimal("3.50"));
        journal.append(logged);

        assertThat(rolledUpEffort(ticketId))
                .as("the cell before the correction")
                .isEqualByComparingTo("3.50");

        journal.reverseEffort(logged.getId(), "logged against the wrong ticket");

        assertThat(rolledUpEffort(ticketId))
                .as("the §4A.4 join must net to zero — the reversal has to land in the same "
                        + "(ticket, cycle, stage, iteration) cell or the original stays counted")
                .isEqualByComparingTo("0.00");
    }

    /** PLAN.md §3.4's roll-up, reduced to the one figure this test is about. */
    private BigDecimal rolledUpEffort(long ticketId) {
        return db.sql("""
                        SELECT COALESCE(SUM(e.hours), 0)
                        FROM ticket_stage_transitions t
                        LEFT JOIN ticket_effort_logs e
                               ON e.ticket_id    = t.ticket_id
                              AND e.stage_code   = t.to_stage
                              AND e.iteration_no = t.iteration_no
                        WHERE t.ticket_id = ? AND t.cycle_no = 1
                        GROUP BY t.id
                        """)
                .param(ticketId)
                .query(BigDecimal.class)
                .single();
    }

    /**
     * {@code uq_effort_corrects} — the A-043 migration. Two reversals of one
     * entry net to {@code -hours}, so the grid reports a negative figure for a
     * stage somebody genuinely worked. There is no reading of that which is not
     * a mistake: a double-submitted form, a retry after a lost response, or two
     * people correcting the same row minutes apart.
     *
     * <p>Enforced by the database rather than the journal, per A-040's boundary
     * — which is why it needs a real schema to prove.
     */
    @Test
    void anEntryCannotBeReversedTwice() {
        long ticketId = seedTicket();
        long userId = seedUser();

        TicketEffortLog logged = new TicketEffortLog();
        logged.setTicketId(ticketId);
        logged.setCycleNo((short) 1);
        logged.setStageCode("DEV");
        logged.setIterationNo((short) 1);
        logged.setUserId(userId);
        logged.setWorkDate(LocalDate.of(2026, 8, 14));
        logged.setHours(new BigDecimal("3.50"));
        journal.append(logged);

        journal.reverseEffort(logged.getId(), "first reversal");

        assertThatThrownBy(() -> journal.reverseEffort(logged.getId(), "the same form, submitted twice"))
                .as("uq_effort_corrects has to bite — the journal deliberately does not restate it")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Chains are legal (D2) and cycles are structurally impossible: a correction
     * can only name a row that already existed, and these rows are immutable, so
     * the graph can only ever point backwards.
     */
    @Test
    void aReversalMayItselfBeReversed() {
        long ticketId = seedTicket();
        long userId = seedUser();

        TicketEffortLog logged = new TicketEffortLog();
        logged.setTicketId(ticketId);
        logged.setCycleNo((short) 1);
        logged.setStageCode("DEV");
        logged.setIterationNo((short) 1);
        logged.setUserId(userId);
        logged.setWorkDate(LocalDate.of(2026, 8, 14));
        logged.setHours(new BigDecimal("8.00"));
        journal.append(logged);

        TicketEffortLog reversal = journal.reverseEffort(logged.getId(), "reversed in error");
        TicketEffortLog undo = journal.reverseEffort(reversal.getId(), "the reversal was the mistake");

        assertThat(undo.getHours()).isEqualByComparingTo("8.00");
        assertThat(db.sql("SELECT COALESCE(SUM(hours), 0) FROM ticket_effort_logs WHERE ticket_id = ?")
                .param(ticketId)
                .query(BigDecimal.class)
                .single())
                .as("+8, -8, +8 — back where it started, with all three rows intact")
                .isEqualByComparingTo("8.00");
    }

    /**
     * A history retraction never removes anything. Asserted against the table
     * rather than a reader, because the rule is that no reader may be given the
     * option — see {@code TicketJournal#reverseHistory}.
     */
    @Test
    void aHistoryRetractionLeavesTheOriginalReadable() {
        long ticketId = seedTicket();
        long userId = seedUser();

        TicketHistory original = new TicketHistory();
        original.setTicketId(ticketId);
        original.setCycleNo((short) 1);
        original.setEventType("STATUS_CHANGED");
        original.setFieldName("status");
        original.setNewValue("IN_QA");
        original.setActorId(userId);
        original.setActorType("USER");
        journal.append(original);

        journal.reverseHistory(original.getId(), userId, "recorded against the wrong cycle");

        entityManager.flush();
        entityManager.clear();

        var rows = history.findByTicketIdOrderByIdAsc(ticketId);
        assertThat(rows)
                .as("both rows render; is_correction decides how, never whether")
                .hasSize(2);
        assertThat(rows.getFirst().isCorrection()).isFalse();
        assertThat(rows.getLast().isCorrection()).isTrue();
        assertThat(rows.getLast().getCorrectsEntryId()).isEqualTo(original.getId());
        assertThat(rows.getLast().getRowHash())
                .as("a correction is chained like any other append")
                .hasSize(ChainDigest.HASH_LENGTH);
    }

    /**
     * The refusal that keeps a legacy or {@code @DirectAppend} row from silently
     * starting a second chain. Written straight through {@code JdbcClient},
     * because the journal is precisely what cannot produce such a row.
     */
    @Test
    void refusesToChainOntoARowThatWasWrittenWithoutOne() {
        long ticketId = seedTicket();
        long userId = seedUser();

        db.sql("INSERT INTO ticket_history (ticket_id, cycle_no, event_type, actor_id, actor_type) "
                        + "VALUES (?, 1, 'CREATED', ?, 'USER')")
                .params(ticketId, userId)
                .update();

        TicketHistory entry = new TicketHistory();
        entry.setTicketId(ticketId);
        entry.setCycleNo((short) 1);
        entry.setEventType("STATUS_CHANGED");
        entry.setActorId(userId);
        entry.setActorType("USER");

        assertThatThrownBy(() -> journal.append(entry))
                .isInstanceOf(AppendRejectedException.class)
                .hasMessageContaining("carries no row_hash");

        assertThat(db.sql("SELECT COUNT(*) FROM ticket_history WHERE ticket_id = ?")
                .param(ticketId)
                .query(Integer.class)
                .single())
                .as("the refused append left nothing behind")
                .isEqualTo(1);
    }
}
