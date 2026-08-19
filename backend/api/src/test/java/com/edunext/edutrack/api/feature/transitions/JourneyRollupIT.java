package com.edunext.edutrack.api.feature.transitions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-058 · the roll-up query, against real MySQL.
 *
 * <p>This has to be an integration test rather than a unit one. What is being
 * asserted is the correctness of a SQL join and a {@code GROUP BY} under
 * {@code ONLY_FULL_GROUP_BY} — the container below enables it explicitly — and
 * a mocked {@code JdbcClient} would assert only that the string I wrote is the
 * string I wrote.
 */
@SpringBootTest
@Testcontainers
class JourneyRollupIT {

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
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
    }

    @Autowired
    JourneyRepository journey;

    @Autowired
    JdbcTemplate jdbc;

    private long ticketId;
    private long ravi;

    /**
     * Isolation is by fresh rows, not by teardown — and that is forced rather
     * than chosen. {@code ticket_stage_transitions} and {@code ticket_effort_logs}
     * are append-only at four layers, so a {@code DELETE} in a cleanup hook is
     * refused by the A-008 trigger with "Immutable table: rows cannot be deleted.
     * The ribbon can never be rewritten." Every test therefore gets its own
     * ticket and its own people, and nothing is ever removed.
     */
    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();

        jdbc.update("INSERT IGNORE INTO projects (project_code, name) VALUES ('ITJ', 'Journey IT')");
        long projectId = jdbc.queryForObject("SELECT id FROM projects WHERE project_code = 'ITJ'", Long.class);

        ravi = insertUser("it_j_ravi_" + n, "DEVELOPER");

        String code = "ITJ-26-%05d".formatted(n);
        jdbc.update("INSERT INTO tickets (ticket_code, title, project_id, level, original_level, current_cycle_no) "
                + "VALUES (?, 'Reopened twice', ?, 'MEDIUM', 'MEDIUM', 2)", code, projectId);
        ticketId = jdbc.queryForObject("SELECT id FROM tickets WHERE ticket_code = ?", Long.class, code);
    }

    /**
     * 🔴 <b>The reason this file exists.</b> PLAN.md §3.4: the blueprint joins
     * effort on {@code (ticket_id, stage_code, iteration_no)} and omits
     * {@code cycle_no}, so a ticket that re-enters the same stage at the same
     * iteration in cycle 2 counts cycle 1's hours twice.
     *
     * <p>Both cycles below are {@code DEV} at {@code iteration_no = 1}, which is
     * exactly the shape a reopen produces and exactly what the defective join
     * cannot tell apart.
     */
    @Test
    @DisplayName("cycle 1's effort does not leak into cycle 2 after a reopen")
    void effortDoesNotLeakAcrossCyclesAfterAReopen() {
        hop(1, 1, 1, "DEV", ravi, 600);
        hop(2, 1, 2, "DEV", ravi, 120);
        effort(1, "DEV", 1, ravi, "6.00");
        effort(2, "DEV", 1, ravi, "1.50");

        assertThat(journey.hops(ticketId, 1))
                .singleElement()
                .extracting(JourneyDtos.JourneyRow::effortHrs)
                .isEqualTo(new BigDecimal("6.00"));

        // 1.50, never 7.50. The defective join returns the sum of both here.
        assertThat(journey.hops(ticketId, 2))
                .singleElement()
                .extracting(JourneyDtos.JourneyRow::effortHrs)
                .isEqualTo(new BigDecimal("1.50"));

        assertThat(journey.cycleTotalHrs(ticketId, 2)).isEqualTo(new BigDecimal("1.50"));
        assertThat(journey.allCyclesTotalHrs(ticketId)).isEqualTo(new BigDecimal("7.50"));
    }

    /**
     * §4A.4's second derived number, and the point of the endpoint: ten hours in
     * the stage against ninety minutes of work is eight and a half hours of
     * queue.
     */
    @Test
    @DisplayName("idle is the stage's duration less the effort logged inside it")
    void idleIsDurationLessEffort() {
        hop(1, 1, 1, "DEV", ravi, 600);
        effort(1, "DEV", 1, ravi, "1.50");

        assertThat(journey.hops(ticketId, 1))
                .singleElement()
                .extracting(JourneyDtos.JourneyRow::idleMins)
                .isEqualTo(510);
    }

    @Test
    @DisplayName("an over-booked stage reports no idle rather than negative idle")
    void idleIsFlooredAtZero() {
        // Three hours booked against a stage open for two. A data-entry question,
        // not a discovery that the ticket waited negative time.
        hop(1, 1, 1, "DEV", ravi, 120);
        effort(1, "DEV", 1, ravi, "3.00");

        assertThat(journey.hops(ticketId, 1))
                .singleElement()
                .extracting(JourneyDtos.JourneyRow::idleMins)
                .isEqualTo(0);
    }

    @Test
    @DisplayName("the open hop reports no duration and no idle, not zero")
    void theOpenHopReportsNulls() {
        jdbc.update("INSERT INTO ticket_stage_transitions "
                + "(ticket_id, cycle_no, iteration_no, seq_no, to_stage, to_user_id, action_code, entered_at) "
                + "VALUES (?, 1, 1, 1, 'DEV', ?, 'FORWARD', '2026-08-01 09:00:00')", ticketId, ravi);

        JourneyDtos.JourneyRow row = journey.hops(ticketId, 1).getFirst();
        assertThat(row.exitedAt()).isNull();
        assertThat(row.durationMins()).isNull();
        // Zero would read as "no waiting so far", which is a claim nobody measured.
        assertThat(row.idleMins()).isNull();
    }

    /**
     * §4A.2 lets a ticket fall to a project-level queue when the receiving role
     * has nobody free (C-050). An inner join to {@code users} would drop the row
     * entirely — hiding the stall the grid exists to show.
     */
    @Test
    @DisplayName("an unassigned hop still appears, with no resource")
    void anUnassignedHopIsStillAHop() {
        jdbc.update("INSERT INTO ticket_stage_transitions "
                + "(ticket_id, cycle_no, iteration_no, seq_no, to_stage, to_user_id, action_code, entered_at, "
                + "exited_at, duration_mins) "
                + "VALUES (?, 1, 1, 1, 'QA', NULL, 'FORWARD', '2026-08-01 09:00:00', '2026-08-01 11:00:00', 120)",
                ticketId);

        JourneyDtos.JourneyRow row = journey.hops(ticketId, 1).getFirst();
        assertThat(row.resource()).isNull();
        assertThat(row.stageCode()).isEqualTo("QA");
        assertThat(row.durationMins()).isEqualTo(120);
    }

    @Test
    @DisplayName("hops come back in the order they happened, not the order they were written")
    void hopsAreOrderedBySeqNo() {
        hop(1, 1, 3, "QA", ravi, 60);
        hop(1, 1, 1, "INTAKE", ravi, 60);
        hop(1, 2, 2, "DEV", ravi, 60);

        assertThat(journey.hops(ticketId, 1))
                .extracting(JourneyDtos.JourneyRow::stageCode)
                .containsExactly("INTAKE", "DEV", "QA");
    }

    @Test
    @DisplayName("per-resource totals cover the cycle asked for and no other")
    void perResourceIsScopedToTheCycle() {
        long anil = insertUser("it_j_anil_" + SEQ.get(), "QA");
        hop(1, 1, 1, "DEV", ravi, 60);
        hop(2, 1, 2, "QA", anil, 60);
        effort(1, "DEV", 1, ravi, "4.00");
        effort(2, "QA", 1, anil, "2.00");

        List<JourneyDtos.PerResource> cycleTwo = journey.perResource(ticketId, 2);
        assertThat(cycleTwo).singleElement().satisfies(p -> {
            assertThat(p.resource().id()).isEqualTo(anil);
            assertThat(p.effortHrs()).isEqualTo(new BigDecimal("2.00"));
        });
    }

    private void hop(int cycleNo, int iterationNo, int seqNo, String stage, Long userId, int durationMins) {
        jdbc.update("INSERT INTO ticket_stage_transitions "
                + "(ticket_id, cycle_no, iteration_no, seq_no, to_stage, to_user_id, action_code, entered_at, "
                + "exited_at, duration_mins) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'FORWARD', '2026-08-01 09:00:00', '2026-08-01 19:00:00', ?)",
                ticketId, cycleNo, iterationNo, seqNo, stage, userId, durationMins);
    }

    private void effort(int cycleNo, String stage, int iterationNo, long userId, String hours) {
        jdbc.update("INSERT INTO ticket_effort_logs "
                + "(ticket_id, cycle_no, stage_code, iteration_no, user_id, work_date, hours) "
                + "VALUES (?, ?, ?, ?, ?, '2026-08-01', ?)",
                ticketId, cycleNo, stage, iterationNo, userId, new BigDecimal(hours));
    }

    /** Never an Admin — the role that has quietly satisfied two other features' tests here. */
    private long insertUser(String username, String roleCode) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", Long.class, roleCode);
        jdbc.update("INSERT INTO users (username, email, full_name, password_hash, role_id, emp_code) "
                + "VALUES (?, ?, ?, 'x', ?, ?)",
                username, username + "@it.example", username, roleId, username);
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }
}
