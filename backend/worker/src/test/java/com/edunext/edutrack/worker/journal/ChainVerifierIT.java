package com.edunext.edutrack.worker.journal;

import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-044 · every detector, proved to fire.
 *
 * <p>A verifier whose tests only ever show it staying quiet is worthless: it
 * would pass identically if {@code verify} returned an empty list
 * unconditionally, and nobody would find out until the night it mattered. So
 * each case here <b>stages the damage deliberately</b> and asserts the
 * corresponding kind comes back.
 *
 * <p>The damage is written with {@code JdbcTemplate}, because every other route
 * into these tables refuses it — which is the point of the four layers, and the
 * reason this suite has to reach past them to test the fifth.
 */
@Testcontainers
@SpringBootTest(classes = com.edunext.edutrack.worker.WorkerApplication.class)
class ChainVerifierIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_chain_it")
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
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("edutrack.outbox.enabled", () -> "false");
        // Nothing here should fire on a timer; every run is invoked directly.
        registry.add("edutrack.chain.verify-cron", () -> "0 0 5 31 2 *");
        registry.add("edutrack.sla.scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.stage-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.pre-breach-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.stale-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.l2-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.ping-pong-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.unassigned-scan-interval", () -> "PT6H");
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TicketJournal journal;

    @Autowired
    ChainVerifier verifier;

    @Autowired
    PlatformTransactionManager txManager;

    TransactionTemplate tx;

    private long ticketId;
    private long userId;

    @BeforeEach
    void seed() {
        tx = new TransactionTemplate(txManager);
        int n = SEQ.incrementAndGet();
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'DEVELOPER'", Long.class);
        jdbc.update("INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id) "
                        + "VALUES (?, ?, ?, 'x', 'Chain IT', ?)",
                "E-V-" + n, "chain.it." + n, "chain.it." + n + "@example.com", roleId);
        userId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        // queryForObject throws on an empty result rather than returning null,
        // so this has to ask for a list — the "if it came back null" form looks
        // right and is unreachable.
        List<Long> existingProjects =
                jdbc.queryForList("SELECT id FROM projects ORDER BY id LIMIT 1", Long.class);
        Long projectId;
        if (existingProjects.isEmpty()) {
            jdbc.update("INSERT INTO projects (project_code, name) VALUES (?, 'Chain IT Project')", "CV" + n);
            projectId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        } else {
            projectId = existingProjects.getFirst();
        }
        jdbc.update("INSERT INTO tickets (ticket_code, project_id, title, level, original_level) "
                + "VALUES (?, ?, 'chain probe', 'LOW', 'LOW')", "CV-26-" + n, projectId);
        ticketId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** Three chained history rows, written the only legitimate way. */
    private void appendGoodRows(int count) {
        for (int i = 0; i < count; i++) {
            int index = i;
            tx.executeWithoutResult(status -> {
                TicketHistory entry = new TicketHistory();
                entry.setTicketId(ticketId);
                entry.setCycleNo((short) 1);
                entry.setEventType("STATUS_CHANGED");
                entry.setFieldName("status");
                entry.setNewValue("STEP_" + index);
                entry.setActorId(userId);
                entry.setActorType("USER");
                journal.append(entry);
            });
        }
    }

    private List<Long> historyIds() {
        return jdbc.queryForList(
                "SELECT id FROM ticket_history WHERE ticket_id = ? ORDER BY id", Long.class, ticketId);
    }

    private List<ChainBreak> verify() {
        return verifier.verify(ticketId);
    }

    private List<ChainBreak.Kind> kinds() {
        return verify().stream().map(ChainBreak::kind).toList();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a sound chain reports nothing and is anchored")
    void soundChainIsAnchoredAndSilent() {
        appendGoodRows(3);

        assertThat(verify()).isEmpty();
        assertThat(verifier.anchorFor(ticketId, ChainVerifier.HISTORY))
                .as("a clean verify records where the chain had got to")
                .hasValueSatisfying(a -> {
                    assertThat(a.rowCount()).isEqualTo(3);
                    assertThat(a.headRowId()).isEqualTo(historyIds().getLast());
                });
    }

    @Test
    @DisplayName("HASH_MISMATCH — a hashed column altered under the triggers")
    void detectsAnAlteredColumn() {
        appendGoodRows(3);
        verify();   // anchor it clean first, so this is not reported as truncation

        // The triggers refuse this through every supported route; disabled here
        // to stage the state a privileged user could reach (PLAN.md §3.5).
        jdbc.update("DROP TRIGGER trg_hist_no_update");
        try {
            jdbc.update("UPDATE ticket_history SET new_value = 'TAMPERED' WHERE id = ?",
                    historyIds().get(1));
        } finally {
            jdbc.update("CREATE TRIGGER trg_hist_no_update BEFORE UPDATE ON ticket_history "
                    + "FOR EACH ROW SIGNAL SQLSTATE '45000' "
                    + "SET MESSAGE_TEXT = 'Immutable table: ticket_history rows cannot be updated. "
                    + "A correction is a new row with is_correction = 1.'");
        }

        assertThat(kinds()).contains(ChainBreak.Kind.HASH_MISMATCH);
    }

    @Test
    @DisplayName("UNCHAINED — a row written around the journal")
    void detectsARowWithNoHash() {
        appendGoodRows(1);
        jdbc.update("INSERT INTO ticket_history (ticket_id, cycle_no, event_type, actor_id, actor_type) "
                + "VALUES (?, 1, 'SMUGGLED', ?, 'USER')", ticketId, userId);

        assertThat(kinds()).contains(ChainBreak.Kind.UNCHAINED);
    }

    @Test
    @DisplayName("BROKEN_LINK — a row naming a parent that is not its predecessor")
    void detectsABrokenLink() {
        appendGoodRows(3);
        jdbc.update("INSERT INTO ticket_history (ticket_id, cycle_no, event_type, actor_id, actor_type, "
                        + "prev_hash, row_hash) VALUES (?, 1, 'INJECTED', ?, 'USER', ?, ?)",
                ticketId, userId, "a".repeat(64), "b".repeat(64));

        assertThat(kinds()).contains(ChainBreak.Kind.BROKEN_LINK);
    }

    @Test
    @DisplayName("FORK — two rows claiming the same parent")
    void detectsAFork() {
        appendGoodRows(2);
        String sharedParent = jdbc.queryForObject(
                "SELECT prev_hash FROM ticket_history WHERE ticket_id = ? ORDER BY id DESC LIMIT 1",
                String.class, ticketId);

        jdbc.update("INSERT INTO ticket_history (ticket_id, cycle_no, event_type, actor_id, actor_type, "
                        + "prev_hash, row_hash) VALUES (?, 1, 'FORKED', ?, 'USER', ?, ?)",
                ticketId, userId, sharedParent, "c".repeat(64));

        assertThat(kinds()).contains(ChainBreak.Kind.FORK);
    }

    /**
     * The shape A-045 produced with eight concurrent writers before the tail
     * read was made a locking read — every writer believing it was first.
     */
    @Test
    @DisplayName("MULTIPLE_GENESIS — more than one row begins the chain")
    void detectsASecondGenesisRow() {
        appendGoodRows(2);
        jdbc.update("INSERT INTO ticket_history (ticket_id, cycle_no, event_type, actor_id, actor_type, "
                        + "prev_hash, row_hash) VALUES (?, 1, 'SECOND_GENESIS', ?, 'USER', NULL, ?)",
                ticketId, userId, "d".repeat(64));

        assertThat(kinds()).contains(ChainBreak.Kind.MULTIPLE_GENESIS);
    }

    /**
     * <b>The break nothing else can see.</b> Delete the chain's last rows and
     * everything remaining verifies perfectly — same hashes, same links, one
     * genesis. Only the anchor knows the chain used to be longer.
     */
    @Test
    @DisplayName("TRUNCATED — the tail deleted after the chain was anchored")
    void detectsTailTruncation() {
        appendGoodRows(3);
        assertThat(verify()).as("anchored clean at three rows").isEmpty();

        List<Long> ids = historyIds();
        jdbc.update("DROP TRIGGER trg_hist_no_delete");
        try {
            jdbc.update("DELETE FROM ticket_history WHERE id = ?", ids.getLast());
        } finally {
            jdbc.update("CREATE TRIGGER trg_hist_no_delete BEFORE DELETE ON ticket_history "
                    + "FOR EACH ROW SIGNAL SQLSTATE '45000' "
                    + "SET MESSAGE_TEXT = 'Immutable table: ticket_history rows cannot be deleted.'");
        }

        List<ChainBreak> breaks = verify();
        assertThat(breaks).extracting(ChainBreak::kind).contains(ChainBreak.Kind.TRUNCATED);
        assertThat(breaks)
                .as("what remains is internally perfect — truncation is the ONLY thing wrong")
                .allMatch(b -> b.kind() == ChainBreak.Kind.TRUNCATED);
    }

    @Test
    @DisplayName("a broken chain is never re-anchored")
    void aBreakDoesNotMoveTheAnchor() {
        appendGoodRows(3);
        verify();
        long anchoredAt = verifier.anchorFor(ticketId, ChainVerifier.HISTORY).orElseThrow().rowCount();

        jdbc.update("INSERT INTO ticket_history (ticket_id, cycle_no, event_type, actor_id, actor_type) "
                + "VALUES (?, 1, 'SMUGGLED', ?, 'USER')", ticketId, userId);
        assertThat(verify()).isNotEmpty();

        assertThat(verifier.anchorFor(ticketId, ChainVerifier.HISTORY).orElseThrow().rowCount())
                .as("anchoring a chain that just failed would file the corruption as known-good, "
                        + "and the next run would compare against it and find nothing wrong")
                .isEqualTo(anchoredAt);
    }
}
