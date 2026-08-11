package com.edunext.edutrack.domain.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A-011 · Testcontainers integration test.
 *
 * Runs every migration in db/migration against a real, disposable MySQL 8.4
 * container — same image and flags as docker-compose.yml — then proves the
 * immutability guarantee (A-008) holds. This is also A-013's negative-test
 * suite: every assertion below is a mutation attempt already verified by
 * hand against the real stack on 2026-08-06; this makes that proof
 * permanent and re-run on every `mvn verify`, in CI and locally.
 *
 * No entities exist yet (Stream B, M3), so this speaks raw JDBC to the
 * container rather than going through Spring/JPA — it is testing the
 * database, not the application.
 */
@Testcontainers
class SchemaIntegrationIT {

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

    private static int migrationsExecuted;
    private static long roleId;
    private static long userId;
    private static long projectId;

    @BeforeAll
    static void migrateAndSeedProbeRows() throws SQLException {
        MigrateResult result = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        migrationsExecuted = result.migrationsExecuted;

        try (Connection c = connect(); Statement s = c.createStatement()) {
            // Probe rows carry codes no migration will ever seed. This suite
            // exercises the schema, not the reference data, so its fixtures must
            // not compete with Stream B's seeds for a real code — 'DEVELOPER'
            // collided with uq_roles_code the day B-001 seeded the six system
            // roles, and took the whole class down in @BeforeAll.
            s.execute("INSERT INTO roles (code, name) VALUES ('IT_PROBE', 'IT Probe Role')");
            roleId = lastInsertId(s);
            s.execute("INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id) "
                    + "VALUES ('IT001', 'it-probe', 'it-probe@test.local', 'x', 'IT Probe', " + roleId + ")");
            userId = lastInsertId(s);
            s.execute("INSERT INTO projects (project_code, name) VALUES ('ITP', 'IT Probe Project')");
            projectId = lastInsertId(s);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static long lastInsertId(Statement s) throws SQLException {
        try (ResultSet rs = s.executeQuery("SELECT LAST_INSERT_ID()")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Fresh ticket per test, so tests never share mutable state with each other. */
    private long seedTicket(Connection c, String code) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("INSERT INTO tickets (ticket_code, project_id, title, level, original_level) "
                    + "VALUES ('" + code + "', " + projectId + ", 'probe', 'LOW', 'LOW')");
            return lastInsertId(s);
        }
    }

    // ------------------------------------------------------------------
    // A-011 — the migrations themselves
    // ------------------------------------------------------------------

    @Test
    void everyMigrationOnTheClasspathApplied() throws SQLException {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0")) {
            rs.next();
            assertThat(rs.getInt(1)).as("no migration recorded a failed run").isZero();
        }
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM flyway_schema_history")) {
            rs.next();
            // Counted from the MigrateResult rather than hard-coded. A literal
            // (it was 7) turns every new migration into an unrelated red build,
            // which trains people to edit the assertion instead of reading it.
            // What this actually needs to prove is that history reflects exactly
            // what Flyway ran — and that holds at any number.
            assertThat(rs.getInt(1))
                    .as("flyway_schema_history reflects exactly the migrations Flyway executed")
                    .isEqualTo(migrationsExecuted);
        }
        assertThat(migrationsExecuted)
                .as("the A-003..A-009 baseline is present at minimum")
                .isGreaterThanOrEqualTo(7);
    }

    @Test
    void schemaHasExpectedShape() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()")) {
                rs.next();
                // 36 domain tables (A-003..A-007) + flyway_schema_history itself,
                // + email_suppressions (D-034), + working_calendar (B-023),
                // + notification_preferences (D-042),
                // + stage_sla_alerts (D-023), + sla_prebreach_alerts (D-021),
                // + stale_ticket_nudges (D-022), + l2_escalations (D-024),
                // + password_reset_tokens (A-027), + password_history (A-028).
                //
                // Stream A: this exact count is a tripwire on every new table,
                // which is why Stream D's migration had to come here to update
                // it, and B-023's after it. That is working as intended — but if
                // you would rather it did not need touching cross-stream,
                // asserting the presence of named tables would give the same
                // protection without the coupling.
                //
                // A-028 note: this went red between A-027 and A-028, because
                // A-027 added password_reset_tokens and did not update the
                // count — its author ran SeedManifestTest and SeedDataIT but not
                // this class, so the tripwire fired on the next branch rather
                // than on the one that tripped it. Both tables are accounted for
                // here. The suggestion above is worth taking: a count is a
                // tripwire nobody can read, and the failure it produced named
                // neither table.
                //
                // D-025 note: +1 for ping_pong_flags, and this is the fourth
                // time a Stream D migration has had to edit a Stream A test to
                // add one. The suggestion three paragraphs up is now overdue —
                // asserting a set of named tables would fail with "missing:
                // ping_pong_flags" instead of "expected 48 but was 46", and no
                // stream would need to touch another's file to add a table.
                assertThat(rs.getInt(1)).isEqualTo(48);
            }
            try (ResultSet rs = s.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE()")) {
                rs.next();
                // A-008: 2 on ticket_history, 2 on ticket_effort_logs,
                // 2 on ticket_stage_transitions (seal + no-delete)
                assertThat(rs.getInt(1)).isEqualTo(8);
            }
        }
    }

    // ------------------------------------------------------------------
    // A-013 — ticket_history: fully immutable
    // ------------------------------------------------------------------

    @Test
    void ticketHistoryRejectsUpdate() throws SQLException {
        try (Connection c = connect()) {
            long ticketId = seedTicket(c, "IT-HU-001");
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO ticket_history (ticket_id, event_type, new_value) "
                        + "VALUES (" + ticketId + ", 'CREATED', 'original')");
            }
            assertThatThrownBy(() -> {
                try (Statement s = c.createStatement()) {
                    s.execute("UPDATE ticket_history SET new_value = 'tampered' WHERE ticket_id = " + ticketId);
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("cannot be updated");
        }
    }

    @Test
    void ticketHistoryRejectsDelete() throws SQLException {
        try (Connection c = connect()) {
            long ticketId = seedTicket(c, "IT-HD-001");
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO ticket_history (ticket_id, event_type, new_value) "
                        + "VALUES (" + ticketId + ", 'CREATED', 'original')");
            }
            assertThatThrownBy(() -> {
                try (Statement s = c.createStatement()) {
                    s.execute("DELETE FROM ticket_history WHERE ticket_id = " + ticketId);
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("cannot be deleted");
        }
    }

    // ------------------------------------------------------------------
    // A-013 — ticket_effort_logs: fully immutable
    // ------------------------------------------------------------------

    @Test
    void effortLogRejectsUpdate() throws SQLException {
        try (Connection c = connect()) {
            long ticketId = seedTicket(c, "IT-EU-001");
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO ticket_effort_logs (ticket_id, cycle_no, user_id, work_date, hours) "
                        + "VALUES (" + ticketId + ", 1, " + userId + ", CURDATE(), 2.5)");
            }
            assertThatThrownBy(() -> {
                try (Statement s = c.createStatement()) {
                    s.execute("UPDATE ticket_effort_logs SET hours = 99 WHERE ticket_id = " + ticketId);
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("cannot be updated");
        }
    }

    @Test
    void effortLogRejectsDelete() throws SQLException {
        try (Connection c = connect()) {
            long ticketId = seedTicket(c, "IT-ED-001");
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO ticket_effort_logs (ticket_id, cycle_no, user_id, work_date, hours) "
                        + "VALUES (" + ticketId + ", 1, " + userId + ", CURDATE(), 2.5)");
            }
            assertThatThrownBy(() -> {
                try (Statement s = c.createStatement()) {
                    s.execute("DELETE FROM ticket_effort_logs WHERE ticket_id = " + ticketId);
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("cannot be deleted");
        }
    }

    // ------------------------------------------------------------------
    // A-013 — ticket_stage_transitions: immutable except the one seal
    // ------------------------------------------------------------------

    @Test
    void stageTransitionRejectsDelete() throws SQLException {
        try (Connection c = connect()) {
            long ticketId = seedTicket(c, "IT-SD-001");
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO ticket_stage_transitions "
                        + "(ticket_id, cycle_no, seq_no, to_stage, action_code, entered_at) "
                        + "VALUES (" + ticketId + ", 1, 1, 'DEV', 'FORWARD', NOW(6))");
            }
            assertThatThrownBy(() -> {
                try (Statement s = c.createStatement()) {
                    s.execute("DELETE FROM ticket_stage_transitions WHERE ticket_id = " + ticketId);
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("cannot be deleted");
        }
    }

    @Test
    void stageTransitionRejectsChangingAnythingButTheSealColumns() throws SQLException {
        try (Connection c = connect()) {
            long ticketId = seedTicket(c, "IT-SC-001");
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO ticket_stage_transitions "
                        + "(ticket_id, cycle_no, seq_no, to_stage, action_code, entered_at) "
                        + "VALUES (" + ticketId + ", 1, 1, 'DEV', 'FORWARD', NOW(6))");
            }
            assertThatThrownBy(() -> {
                try (Statement s = c.createStatement()) {
                    s.execute("UPDATE ticket_stage_transitions SET to_stage = 'QA' "
                            + "WHERE ticket_id = " + ticketId + " AND seq_no = 1");
                }
            }).isInstanceOf(SQLException.class)
              .hasMessageContaining("Only exited_at, duration_mins and is_current");
        }
    }

    @Test
    void stageTransitionSealSucceedsExactlyOnce() throws SQLException {
        try (Connection c = connect()) {
            long ticketId = seedTicket(c, "IT-SE-001");
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO ticket_stage_transitions "
                        + "(ticket_id, cycle_no, seq_no, to_stage, action_code, entered_at) "
                        + "VALUES (" + ticketId + ", 1, 1, 'DEV', 'FORWARD', NOW(6))");
            }

            // the one permitted mutation: exited_at NULL -> timestamp
            try (Statement s = c.createStatement()) {
                int updated = s.executeUpdate(
                        "UPDATE ticket_stage_transitions "
                                + "SET exited_at = NOW(6), duration_mins = 90, is_current = 0 "
                                + "WHERE ticket_id = " + ticketId + " AND seq_no = 1");
                assertThat(updated).isEqualTo(1);
            }

            // A-009's generated column must recompute once is_current drops
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT current_ticket_id FROM ticket_stage_transitions "
                                 + "WHERE ticket_id = " + ticketId + " AND seq_no = 1")) {
                rs.next();
                rs.getLong(1);
                assertThat(rs.wasNull()).as("current_ticket_id clears once the stage is sealed").isTrue();
            }

            // sealing twice must be rejected — this is the guard that stops
            // a stray second write from rewriting the duration the whole
            // ribbon exists to report
            assertThatThrownBy(() -> {
                try (Statement s = c.createStatement()) {
                    s.execute("UPDATE ticket_stage_transitions SET duration_mins = 1 "
                            + "WHERE ticket_id = " + ticketId + " AND seq_no = 1");
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("already sealed");
        }
    }
}
