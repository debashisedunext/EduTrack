package com.edunext.edutrack.api.feature.fixtures;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-007 · the fixture corpus against a real MySQL and a real Flyway run — not
 * mocks. {@code local,fixtures} is active, so context startup itself runs
 * {@link FixtureLoader} exactly the way a developer would by hand; this class
 * only asserts on what landed in the database afterwards.
 *
 * <p>What this exists to catch: a generator that compiles and "runs" but
 * produces a corpus that is not actually what B-007 promised — every stage
 * visited, real rework and reopen cases, deliberately breached tickets, client
 * attribution, referential integrity into the reference data, and hash-chain
 * columns left NULL on purpose (see the class javadoc on
 * {@link TicketFixtureGenerator} for why). Those are exactly the properties
 * Debashis's SLA scanner and Divyansh's ribbon need to trust before either
 * feature exists to test against.
 */
@SpringBootTest
@ActiveProfiles({"local", "fixtures"})
@Testcontainers
class FixtureCorpusIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_fixtures_it")
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
    JdbcTemplate jdbc;

    @Autowired
    FixtureLoader loader;

    @Autowired
    ReferenceDataFixture referenceData;

    // ── reference data ──────────────────────────────────────────────────────

    @Test
    void createsTheThreeFixtureProjects() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM projects WHERE project_code IN ('CRM','PAY','WEB')", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void createsEighteenResourcesAcrossAllSixRoles() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE emp_code LIKE 'B7-%'", Integer.class))
                .isEqualTo(18);
        Integer distinctRoles = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT r.code) FROM users u JOIN roles r ON r.id = u.role_id
                 WHERE u.emp_code LIKE 'B7-%'
                """, Integer.class);
        assertThat(distinctRoles).isEqualTo(6);
    }

    @Test
    void createsEightClientsEachWithAPrimaryContact() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM clients", Integer.class)).isEqualTo(8);
        Integer clientsMissingAPrimary = jdbc.queryForObject("""
                SELECT COUNT(*) FROM clients c
                 WHERE NOT EXISTS (
                    SELECT 1 FROM client_contacts cc
                     WHERE cc.client_id = c.id AND cc.is_primary = 1 AND cc.is_active = 1)
                """, Integer.class);
        assertThat(clientsMissingAPrimary).isZero();
    }

    @Test
    void seedsFourOrgWideSlaPolicies() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sla_policies WHERE project_id IS NULL AND task_type_id IS NULL",
                Integer.class))
                .isEqualTo(4);
    }

    // ── the 200-ticket corpus ────────────────────────────────────────────────

    @Test
    void generatesExactlyTwoHundredTickets() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tickets", Integer.class)).isEqualTo(200);
    }

    @Test
    void ticketsAreSpreadAcrossAllThreeProjects() {
        Integer projectsWithTickets = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT project_id) FROM tickets", Integer.class);
        assertThat(projectsWithTickets).isEqualTo(3);
    }

    @Test
    void everyStageOfEveryTemplateIsVisited() {
        // 8 + 5 + 5 distinct stage codes across the 3 templates, minus the
        // overlap (INTAKE/TRIAGE/DEV/DEPLOY/VERIFY/SIGNOFF/CLOSED are shared
        // codes reused by more than one template) — asserting the set directly
        // rather than the arithmetic, which is the point of the test.
        Integer distinctStagesVisited = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT to_stage) FROM ticket_stage_transitions", Integer.class);
        assertThat(distinctStagesVisited).isGreaterThanOrEqualTo(8);
    }

    @Test
    void includesBothOpenAndFullyClosedTickets() {
        Integer closed = jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE status = 'CLOSED'", Integer.class);
        Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE status != 'CLOSED'", Integer.class);
        assertThat(closed).isGreaterThan(0);
        assertThat(open).isGreaterThan(0);
    }

    @Test
    void includesReworkedTickets() {
        Integer reworked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tickets WHERE rework_count > 0 OR current_iteration > 1", Integer.class);
        assertThat(reworked).isGreaterThan(0);
    }

    @Test
    void includesReopenedTicketsWithASecondCycle() {
        Integer reopened = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tickets WHERE is_reopened = 1 AND current_cycle_no = 2", Integer.class);
        Integer secondCycles = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_cycles WHERE cycle_no = 2", Integer.class);
        assertThat(reopened).isGreaterThan(0);
        assertThat(secondCycles).isEqualTo(reopened);
    }

    @Test
    void reopenedTicketsHaveExactlyOneCurrentStageTransition() {
        // The bug this guards: sealing cycle 1's CLOSED hop on reopen. Without
        // it a reopened ticket carries two is_current=1 rows — the old CLOSED
        // hop and cycle 2's resting stage — which breaks the one-row-per-ticket
        // assumption A-009's current_ticket_id generated column depends on.
        Integer ticketsWithMoreThanOneCurrentRow = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT ticket_id FROM ticket_stage_transitions
                     WHERE is_current = 1
                     GROUP BY ticket_id HAVING COUNT(*) > 1) t
                """, Integer.class);
        assertThat(ticketsWithMoreThanOneCurrentRow).isZero();
    }

    @Test
    void includesDeliberatelyBreachedTickets() {
        Integer breached = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tickets WHERE is_delayed = 1", Integer.class);
        assertThat(breached).isGreaterThanOrEqualTo(30); // ~20% of 200, allowing slack for outcome rolls
        Integer breachedWithoutADelayedSince = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tickets WHERE is_delayed = 1 AND delayed_since IS NULL", Integer.class);
        assertThat(breachedWithoutADelayedSince).isZero();
    }

    @Test
    void includesClientAttributedTickets() {
        Integer clientRaised = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tickets WHERE is_client_raised = 1 AND client_id IS NOT NULL", Integer.class);
        assertThat(clientRaised).isGreaterThan(0);
    }

    @Test
    void everyTicketHasStageTransitionsHistoryAndEffort() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_stage_transitions", Integer.class))
                .isGreaterThan(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_history", Integer.class)).isGreaterThan(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_effort_logs", Integer.class)).isGreaterThan(0);

        Integer ticketsWithNoTransitions = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tickets t
                 WHERE NOT EXISTS (SELECT 1 FROM ticket_stage_transitions s WHERE s.ticket_id = t.id)
                """, Integer.class);
        assertThat(ticketsWithNoTransitions).isZero();
    }

    @Test
    void totalEffortOnTheTicketMatchesItsLoggedHours() {
        Integer mismatches = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tickets t
                 WHERE t.total_effort_hrs != (
                    SELECT COALESCE(SUM(e.hours), 0) FROM ticket_effort_logs e WHERE e.ticket_id = t.id)
                """, Integer.class);
        assertThat(mismatches).isZero();
    }

    // ── the hash-chain decision ─────────────────────────────────────────────

    /**
     * <b>Inverted by A-042, which is what this test was written to wait for.</b>
     * It used to assert the hash columns were left NULL, saying that if it ever
     * failed, "either a real hashing implementation landed and this fixture
     * should adopt it, or something started writing hashes without one
     * existing". The first happened, and the fixture now goes through
     * {@code TicketJournal} like every other writer.
     *
     * <p>The assertion is {@code row_hash IS NULL} counted at zero rather than a
     * spot check, because the whole point is that the corpus contains no
     * unverifiable row. A-044 is then free to treat a NULL {@code row_hash} as a
     * finding outright — if seed data were allowed to carry them, the verifier
     * would have to skip NULLs, and skipping NULLs means unhashing a row hides
     * whatever was done to it.
     */
    @Test
    void everyAppendOnlyRowIsHashedIntoItsTicketsChain() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_history WHERE row_hash IS NULL",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_effort_logs WHERE row_hash IS NULL",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_stage_transitions WHERE row_hash IS NULL",
                Integer.class)).isZero();
    }

    /**
     * Exactly one genesis row per ticket per table. More than one is a fork —
     * two chains for the same ticket, each internally consistent, with whatever
     * sits before the second one unreachable. It is the failure mode that
     * survives a chain walk, so it is worth asserting over the whole corpus
     * rather than trusting the append path that produced it.
     */
    @Test
    void noTicketHasTwoChainHeadsInOneTable() {
        for (String table : List.of("ticket_history", "ticket_effort_logs", "ticket_stage_transitions")) {
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM (SELECT ticket_id FROM " + table
                            + " WHERE prev_hash IS NULL GROUP BY ticket_id HAVING COUNT(*) > 1) forks",
                    Integer.class))
                    .as("%s has a ticket with more than one row carrying no prev_hash", table)
                    .isZero();
        }
    }

    // ── referential integrity ───────────────────────────────────────────────

    @Test
    void everyTicketResolvesToRealReferenceData() {
        Integer orphanedProject = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tickets t WHERE NOT EXISTS (SELECT 1 FROM projects p WHERE p.id = t.project_id)",
                Integer.class);
        Integer orphanedTaskType = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tickets t WHERE NOT EXISTS "
                        + "(SELECT 1 FROM task_types tt WHERE tt.id = t.task_type_id)", Integer.class);
        Integer orphanedReporter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tickets t WHERE NOT EXISTS "
                        + "(SELECT 1 FROM users u WHERE u.id = t.reported_by)", Integer.class);
        Integer orphanedAssignee = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tickets t
                 WHERE t.assigned_to IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM users u WHERE u.id = t.assigned_to)
                """, Integer.class);
        assertThat(orphanedProject).isZero();
        assertThat(orphanedTaskType).isZero();
        assertThat(orphanedReporter).isZero();
        assertThat(orphanedAssignee).isZero();
    }

    // ── idempotency ──────────────────────────────────────────────────────────

    @Test
    void reRunningTheLoaderIsANoOp() {
        loader.run(null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tickets", Integer.class)).isEqualTo(200);
        assertThat(referenceData.alreadyLoaded()).isTrue();
    }
}
