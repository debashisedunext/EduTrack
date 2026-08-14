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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
                    // A-042 fills these. Until then the column is honestly empty
                    // rather than carrying something a caller invented.
                    assertThat(h.getRowHash()).isNull();
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
}
