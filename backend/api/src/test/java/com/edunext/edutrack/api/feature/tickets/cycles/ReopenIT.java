package com.edunext.edutrack.api.feature.tickets.cycles;

import com.edunext.edutrack.api.feature.tickets.TicketWire;
import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.api.security.scope.TicketNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C-038 · the reopen transaction against a real MySQL.
 *
 * <h2>What this proves that {@code ReopenServiceTest} cannot</h2>
 *
 * <p>The unit test asserts what the service <em>intends</em> — the fields it
 * sets, the rows it hands to a mock. Four of C-038's guarantees are properties
 * of the database and are invisible to a mock:
 *
 * <ol>
 *   <li><b>The history entry is really hash-chained.</b> A mocked journal
 *       records the call; only a real one writes a {@code row_hash}. If the
 *       append ever stopped going through {@code TicketJournal}, the unit test
 *       would still pass and A-044's verifier would find an unhashed row months
 *       later.</li>
 *   <li><b>{@code pcd_open} picks the ticket back up.</b> The generated column
 *       is {@code IF(actual_close_date IS NULL, planned_close_date, NULL)}, so
 *       "clearing the close date puts the ticket back in the SLA scan" is a
 *       claim about MySQL and not about Java. This is the assertion that would
 *       have caught a reopen that left the ticket invisible to every open-ticket
 *       query.</li>
 *   <li><b>The transaction is one transaction.</b> A refusal must leave nothing
 *       behind, and only a real rollback can show that.</li>
 *   <li><b>The append-only triggers are in force</b> around the one mutation
 *       C-038 makes to a hash-chained table — none. Asserted directly, because
 *       "we never update history" is worth proving rather than trusting.</li>
 * </ol>
 *
 * <p>Fixtures are never truncated, following {@code TicketDetailIT}: A-008's
 * triggers refuse {@code DELETE} on the three append-only tables, so each test
 * makes its own project, user and ticket and scopes every assertion to them.
 */
@SpringBootTest
@Testcontainers
class ReopenIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_reopen_it")
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
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
    }

    private static final String REASON = "Client reports the defect has recurred in production.";

    @Autowired
    ReopenService service;

    @Autowired
    JdbcTemplate jdbc;

    private long projectId;
    private long userId;
    private long templateId;
    private long ticketId;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, 'Reopen IT', 'ACTIVE')",
                "RPN" + suffix());
        projectId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'PM'", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id, is_active)
                VALUES (?, ?, ?, 'x', 'Reopen IT', ?, 1)
                """, "RPN" + suffix(), "rpn." + suffix(),
                "rpn" + suffix() + "@example.test", roleId);
        userId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        // A seeded template that actually defines TRIAGE, so the restart-stage
        // validation is exercised against real workflow_stages rather than skipped.
        templateId = jdbc.queryForObject(
                "SELECT template_id FROM workflow_stages WHERE stage_code = 'TRIAGE' LIMIT 1", Long.class);

        ticketId = insertClosedTicket((short) 1);
        insertCycle(ticketId, (short) 1, new BigDecimal("24.50"));
        // Cycle 1's 24.50 hours as real effort logs — the rows the headline
        // guarantee is about.
        //
        // Two entries, not one, because `ck_effort_hours` caps a single entry at
        // 24: it is a per-entry sanity bound (nobody logs 25 hours of one day's
        // work on one stage), not a bound on a cycle. A cycle total of 24.50 is
        // entirely ordinary and simply cannot be one row. Split across two work
        // dates so the fixture is the shape a real effort record has, and so the
        // logs sum to the cycle figure beside them — a fixture whose parts
        // disagree undermines the very thing this class asserts, which is that
        // the effort record survives a reopen untouched.
        insertEffort(ticketId, (short) 1, "12.00", "2026-08-10");
        insertEffort(ticketId, (short) 1, "12.50", "2026-08-11");
    }

    // ── the transaction ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("the transaction")
    class Transaction {

        @Test
        @DisplayName("seals cycle N, opens N+1 and moves the ticket, all committed together")
        void allSixWritesLand() {
            TicketWire.Ticket answered = service.reopen(pm(), ticketId, request());

            assertThat(answered.status()).isEqualTo("REOPENED");
            assertThat(answered.currentCycleNo()).isEqualTo(2);
            assertThat(answered.reopenCount()).isEqualTo(1);
            assertThat(answered.actualCloseDate()).isNull();

            Map<String, Object> ticket = ticketRow();
            assertThat(ticket.get("status")).isEqualTo("REOPENED");
            assertThat(number(ticket, "current_cycle_no")).isEqualTo(2);
            assertThat(number(ticket, "reopen_count")).isEqualTo(1);
            assertThat(ticket.get("is_reopened")).isEqualTo(true);
            assertThat(ticket.get("actual_close_date")).isNull();
            assertThat(ticket.get("current_stage")).isEqualTo("TRIAGE");
            assertThat(number(ticket, "current_iteration")).isEqualTo(1);
            assertThat(number(ticket, "rework_count"))
                    .as("counts backward moves over the ticket's whole life — a new cycle does not reset it")
                    .isEqualTo(2);

            assertThat(cycleCount()).isEqualTo(2);
            assertThat(sealed((short) 1)).as("cycle 1 sealed").isTrue();
            assertThat(sealed((short) 2)).as("cycle 2 open").isFalse();
            assertThat(reopenReason((short) 2)).isEqualTo(REASON);
        }

        /**
         * 🔴 The generated column, and the reason clearing the close date matters.
         * {@code pcd_open} is {@code IF(actual_close_date IS NULL,
         * planned_close_date, NULL)} — it is what {@code ix_tickets_pcd_open}
         * indexes and what Stream D's breach sweep reads. A reopen that kept the
         * close date would leave this NULL and the ticket would sit in somebody's
         * queue, tracked by nothing.
         */
        @Test
        @DisplayName("pcd_open comes back, so the SLA scan sees the ticket again")
        void pcdOpenIsRestored() {
            assertThat(ticketRow().get("pcd_open"))
                    .as("a closed ticket is out of the open-PCD index")
                    .isNull();

            service.reopen(pm(), ticketId, new ReopenDtos.ReopenRequest(
                    REASON, null, null, Instant.parse("2026-09-30T12:00:00Z"), null));

            assertThat(ticketRow().get("pcd_open"))
                    .as("reopened, so the generated column exposes the planned close date again")
                    .isNotNull();
        }

        /**
         * 🔴 Proof the entry went through {@link
         * com.edunext.edutrack.domain.journal.TicketJournal} rather than round a
         * repository: only the journal writes the chain, and a mock cannot tell
         * the difference.
         */
        @Test
        @DisplayName("the REOPENED entry is really hash-chained")
        void historyIsChained() {
            service.reopen(pm(), ticketId, request());

            Map<String, Object> entry = jdbc.queryForMap("""
                    SELECT event_type, field_name, old_value, new_value, cycle_no, remarks,
                           actor_id, actor_type, prev_hash, row_hash, is_correction
                    FROM ticket_history WHERE ticket_id = ? ORDER BY id DESC LIMIT 1
                    """, ticketId);

            assertThat(entry.get("event_type")).isEqualTo("REOPENED");
            assertThat(entry.get("field_name")).isEqualTo("status");
            assertThat(entry.get("old_value")).isEqualTo("CLOSED");
            assertThat(entry.get("new_value")).isEqualTo("REOPENED");
            assertThat(number(entry, "cycle_no"))
                    .as("stamped with the NEW cycle, so a cycle-2 history filter opens with why cycle 2 exists")
                    .isEqualTo(2);
            assertThat(entry.get("remarks")).isEqualTo(REASON);
            assertThat(entry.get("actor_id")).isEqualTo(userId);
            assertThat(entry.get("actor_type")).isEqualTo("USER");
            assertThat(entry.get("is_correction")).isEqualTo(false);
            assertThat((String) entry.get("row_hash"))
                    .as("A-042's chain link — NULL here means the append bypassed the journal")
                    .isNotNull()
                    .hasSize(64);
            assertThat((String) entry.get("prev_hash"))
                    .as("this ticket's chain starts here, so the genesis row has no predecessor")
                    .isNull();
        }

        /**
         * The chain is per ticket and linear. A second reopen must hash onto the
         * first, not start again — a fork is what A-044 reports as tampering.
         */
        @Test
        @DisplayName("a second reopen chains onto the first rather than forking")
        void chainStaysLinear() {
            service.reopen(pm(), ticketId, request());
            reclose();
            service.reopen(pm(), ticketId, request());

            List<Map<String, Object>> chain = jdbc.queryForList("""
                    SELECT prev_hash, row_hash FROM ticket_history
                    WHERE ticket_id = ? ORDER BY id ASC
                    """, ticketId);

            assertThat(chain).hasSize(2);
            assertThat(chain.get(0).get("prev_hash")).isNull();
            assertThat(chain.get(1).get("prev_hash"))
                    .as("links onto its predecessor's row_hash")
                    .isEqualTo(chain.get(0).get("row_hash"));
            assertThat(number(ticketRow(), "current_cycle_no")).isEqualTo(3);
            assertThat(number(ticketRow(), "reopen_count")).isEqualTo(2);
            assertThat(cycleCount()).isEqualTo(3);
        }
    }

    // ── 🔴 cycle N's effort is never touched ─────────────────────────────────

    @Nested
    @DisplayName("cycle N's effort")
    class EffortIsUntouched {

        @Test
        @DisplayName("the log, the cycle figure and the grand total all survive unchanged")
        void nothingAboutEffortMoves() {
            service.reopen(pm(), ticketId, request());

            assertThat(effortLogCount((short) 1))
                    .as("cycle 1's effort logs are still there, uncorrected")
                    // Two, because `ck_effort_hours` caps one entry at 24 hours
                    // and cycle 1 holds 24.50 — see the fixture. What is being
                    // asserted is survival, not the count.
                    .isEqualTo(2);
            assertThat(effortLogCount((short) 2))
                    .as("a reopen logs no effort of its own")
                    .isZero();
            assertThat(cycleEffort((short) 1))
                    .as("the sealed cycle's final figure stops moving because the cycle stopped")
                    .isEqualByComparingTo("24.50");
            assertThat(cycleEffort((short) 2))
                    .as("the new cycle starts at zero — anything else is cycle 1's hours leaking in")
                    .isEqualByComparingTo("0.00");
            assertThat((BigDecimal) ticketRow().get("total_effort_hrs"))
                    .as("Σ across all cycles already included cycle 1; adding here would double-count")
                    .isEqualByComparingTo("24.50");
        }

        /**
         * The other half of "never touched": no compensating row either. A reversal
         * would net to the same total and destroy the record of the work.
         */
        @Test
        @DisplayName("no correction row is written against cycle 1's effort")
        void noCompensatingRow() {
            service.reopen(pm(), ticketId, request());

            Integer corrections = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ticket_effort_logs WHERE ticket_id = ? AND is_correction = 1",
                    Integer.class, ticketId);
            assertThat(corrections).isZero();
        }

        /**
         * A-008, asserted rather than assumed. C-038 makes no {@code UPDATE} to a
         * hash-chained table, and this proves the floor under that: even root
         * cannot rewrite the entry the reopen just wrote.
         */
        @Test
        @DisplayName("the history entry cannot be updated afterwards, by anyone")
        void theTriggerHoldsTheFloor() {
            service.reopen(pm(), ticketId, request());
            Long entryId = jdbc.queryForObject(
                    "SELECT id FROM ticket_history WHERE ticket_id = ? ORDER BY id DESC LIMIT 1",
                    Long.class, ticketId);

            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE ticket_history SET remarks = 'rewritten' WHERE id = ?", entryId))
                    .as("trg_hist_no_update refuses every UPDATE on this table")
                    .isInstanceOf(Exception.class);
        }
    }

    // ── the planned close date ───────────────────────────────────────────────

    @Nested
    @DisplayName("the planned close date")
    class PlannedCloseDate {

        /**
         * Never cycle N's, which is in the past. Asserted as "not the old value,
         * and in the future if resolved at all" rather than as a fixed instant: what
         * the §6 ladder answers depends on the seeded SLA masters, and pinning a
         * date here would make this test about the seed rather than about the reopen.
         */
        @Test
        @DisplayName("is recomputed, never the sealed cycle's past date")
        void isRecomputedNotReused() {
            Instant closedPcd = (Instant) jdbc.queryForObject(
                    "SELECT planned_close_date FROM ticket_cycles WHERE ticket_id = ? AND cycle_no = 1",
                    (rs, n) -> rs.getTimestamp(1).toInstant(), ticketId);
            Instant before = Instant.now();

            service.reopen(pm(), ticketId, request());

            Instant reopened = (Instant) jdbc.queryForObject(
                    "SELECT planned_close_date FROM ticket_cycles WHERE ticket_id = ? AND cycle_no = 2",
                    (rs, n) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), ticketId);

            assertThat(reopened).isNotEqualTo(closedPcd);
            if (reopened != null) {
                assertThat(reopened)
                        .as("a reopened ticket must not be born breached")
                        .isAfter(before);
            }
        }

        @Test
        @DisplayName("takes the caller's date verbatim when supplied")
        void takesTheCallersDate() {
            Instant chosen = Instant.parse("2026-09-15T12:00:00Z");

            service.reopen(pm(), ticketId, new ReopenDtos.ReopenRequest(
                    REASON, null, null, chosen, new BigDecimal("6.50")));

            assertThat((Instant) jdbc.queryForObject(
                    "SELECT planned_close_date FROM tickets WHERE id = ?",
                    (rs, n) -> rs.getTimestamp(1).toInstant(), ticketId))
                    .isEqualTo(chosen);
            assertThat((BigDecimal) ticketRow().get("estimated_effort_hrs"))
                    .isEqualByComparingTo("6.50");
        }
    }

    // ── refusals leave nothing behind ────────────────────────────────────────

    @Nested
    @DisplayName("a refusal leaves nothing behind")
    class Refusals {

        /**
         * The rollback, which is the half a unit test cannot show. A 422 that had
         * already sealed cycle 1 would leave a closed ticket nobody can reopen.
         */
        @Test
        @DisplayName("RESOLVED is 422, and cycle 1 is still unsealed with no new cycle")
        void resolvedRollsBackWhole() {
            jdbc.update("UPDATE tickets SET status = 'RESOLVED' WHERE id = ?", ticketId);

            assertThatThrownBy(() -> service.reopen(pm(), ticketId, request()))
                    .isInstanceOf(TicketNotClosedException.class);

            assertThat(cycleCount()).isEqualTo(1);
            assertThat(sealed((short) 1)).isFalse();
            assertThat(historyCount()).isZero();
            assertThat(ticketRow().get("status")).isEqualTo("RESOLVED");
        }

        @Test
        @DisplayName("reopening twice without reclosing is 422 the second time")
        void doubleReopenIsRefused() {
            service.reopen(pm(), ticketId, request());

            assertThatThrownBy(() -> service.reopen(pm(), ticketId, request()))
                    .isInstanceOf(TicketNotClosedException.class);

            assertThat(cycleCount()).as("no third cycle").isEqualTo(2);
            assertThat(number(ticketRow(), "reopen_count")).isEqualTo(1);
            assertThat(historyCount()).as("no second history entry").isEqualTo(1);
        }

        @Test
        @DisplayName("a stage outside the ticket's template is refused before anything is written")
        void unknownStageRollsBack() {
            assertThatThrownBy(() -> service.reopen(pm(), ticketId, new ReopenDtos.ReopenRequest(
                    REASON, "NOT_A_STAGE", null, null, null)))
                    .isInstanceOf(UnknownRestartStageException.class);

            assertThat(cycleCount()).isEqualTo(1);
            assertThat(sealed((short) 1)).isFalse();
            assertThat(historyCount()).isZero();
        }

        /**
         * A-035 · out of scope is indistinguishable from absent. A Developer sees
         * {@code assigned_to = me}, and this ticket is assigned to nobody.
         */
        @Test
        @DisplayName("a ticket outside the caller's scope is 404, not 403")
        void outOfScopeIs404() {
            assertThatThrownBy(() -> service.reopen(
                    caller("DEVELOPER", List.of()), ticketId, request()))
                    .isInstanceOf(TicketNotFoundException.class);

            assertThat(cycleCount()).isEqualTo(1);
            assertThat(historyCount()).isZero();
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static ReopenDtos.ReopenRequest request() {
        return new ReopenDtos.ReopenRequest(REASON, null, null, null, null);
    }

    /** Assigned to nobody, so the DEVELOPER scope test has a ticket to miss. */
    private long insertClosedTicket(short currentCycle) {
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, level, original_level, status,
                                     workflow_template_id, current_cycle_no, current_iteration,
                                     rework_count, current_stage, estimated_effort_hrs,
                                     total_effort_hrs, planned_close_date, actual_close_date)
                VALUES (?, ?, 'reopen probe', 'HIGH', 'MEDIUM', 'CLOSED', ?, ?, 3, 2, 'CLOSED',
                        12.00, 24.50, '2026-08-10 12:00:00', '2026-08-11 16:20:00')
                """, "RP-26-" + suffix(), projectId, templateId, currentCycle);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertCycle(long ticket, short cycle, BigDecimal effort) {
        jdbc.update("""
                INSERT INTO ticket_cycles (ticket_id, cycle_no, start_date, assigned_to, level,
                                           planned_close_date, actual_close_date, effort_hrs, is_sealed)
                VALUES (?, ?, '2026-08-03 09:00:00', ?, 'HIGH',
                        '2026-08-10 12:00:00', '2026-08-11 16:20:00', ?, 0)
                """, ticket, cycle, userId, effort);
    }

    /**
     * A whole-number column out of a JDBC row, as an {@code int}.
     *
     * <p><b>Not {@code isEqualTo((short) 2)}.</b> These are {@code SMALLINT}
     * columns and Connector/J answers {@code getObject()} on one with an
     * {@code Integer}, so an assertion against a boxed {@code Short} fails on the
     * box while the value is right — {@code expected: 2 (Short) but was: 2
     * (Integer)}, which reads like a behaviour failure and is not one.
     *
     * <p>Comparing as {@code int} also keeps the driver's type mapping out of the
     * assertion: writing {@code isEqualTo(2)} against the raw object would pass
     * today and break again if the column widened to {@code INT} or the mapping
     * changed, for a reason nobody reading it would guess.
     */
    private static int number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).intValue();
    }

    /**
     * One effort entry. {@code hours} must satisfy {@code ck_effort_hours} —
     * non-zero, positive unless it is a correction, and <b>at most 24</b>, which
     * is a bound on one entry rather than on a cycle.
     */
    private void insertEffort(long ticket, short cycle, String hours, String workDate) {
        jdbc.update("""
                INSERT INTO ticket_effort_logs (ticket_id, cycle_no, stage_code, iteration_no,
                                                user_id, work_date, hours)
                VALUES (?, ?, 'DEV', 1, ?, ?, ?)
                """, ticket, cycle, userId, workDate, new BigDecimal(hours));
    }

    /** Close the ticket again so it can be reopened into a third cycle. */
    private void reclose() {
        jdbc.update("""
                UPDATE tickets SET status = 'CLOSED', actual_close_date = '2026-08-20 10:00:00'
                WHERE id = ?
                """, ticketId);
    }

    private Authentication pm() {
        return caller("PM", List.of(projectId));
    }

    private Authentication caller(String role, List<Long> projectIds) {
        return new UsernamePasswordAuthenticationToken(
                new DevPrincipal(userId, "rpn.fixture", "Fixture", role, projectIds, List.of()),
                null, List.of());
    }

    private Map<String, Object> ticketRow() {
        return jdbc.queryForMap("SELECT * FROM tickets WHERE id = ?", ticketId);
    }

    private int cycleCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_cycles WHERE ticket_id = ?", Integer.class, ticketId);
    }

    private boolean sealed(short cycleNo) {
        return jdbc.queryForObject(
                "SELECT is_sealed FROM ticket_cycles WHERE ticket_id = ? AND cycle_no = ?",
                Boolean.class, ticketId, cycleNo);
    }

    private String reopenReason(short cycleNo) {
        return jdbc.queryForObject(
                "SELECT reopen_reason FROM ticket_cycles WHERE ticket_id = ? AND cycle_no = ?",
                String.class, ticketId, cycleNo);
    }

    private BigDecimal cycleEffort(short cycleNo) {
        return jdbc.queryForObject(
                "SELECT effort_hrs FROM ticket_cycles WHERE ticket_id = ? AND cycle_no = ?",
                BigDecimal.class, ticketId, cycleNo);
    }

    private int effortLogCount(short cycleNo) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_effort_logs WHERE ticket_id = ? AND cycle_no = ?",
                Integer.class, ticketId, cycleNo);
    }

    private int historyCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_history WHERE ticket_id = ?", Integer.class, ticketId);
    }

    /** See {@code TicketDetailIT.suffix()} — base 36, seven wide, for uq_projects_code. */
    private static String suffix() {
        String base36 = Long.toString(Math.abs(System.nanoTime()), 36);
        return base36.length() <= 7 ? base36 : base36.substring(base36.length() - 7);
    }
}
