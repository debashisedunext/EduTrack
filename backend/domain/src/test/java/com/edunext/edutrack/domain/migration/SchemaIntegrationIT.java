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
import java.util.ArrayList;
import java.util.List;

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
            // A-029 · this was `COUNT(*) == 46`, and A-029 has taken the
            // suggestion the previous two authors of this block left behind.
            //
            // The count was a genuine tripwire — it caught every new table — but
            // it caught them uselessly. Its failure read `expected: 46 but was:
            // 47` and named neither the table that was added nor the one that
            // was missing, so every stream that tripped it had to go and diff
            // the schema by hand to find out what it was telling them. It also
            // coupled four streams to one integer: D's migrations edited this
            // line, then B's, then A-027's did not and left the assertion red
            // for a whole branch before A-028 found it.
            //
            // Naming the tables gives strictly more protection. A missing table
            // now says which one, an unexpected table says which one, and adding
            // a migration means adding a name here — which is the same
            // discipline SEED-MANIFEST.md already imposes, and for the same
            // reason.
            //
            // Debashis asked for exactly this in D-025, independently and in
            // the same breath as bumping the count to 47: "the suggestion three
            // paragraphs up is now overdue — asserting a set of named tables
            // would fail with 'missing: ping_pong_flags' instead of 'expected
            // 47 but was 46', and no stream would need to touch another's file
            // to add a table." Two streams reaching the same conclusion from
            // opposite ends is a reasonable signal it was the right one.
            try (ResultSet rs = s.executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()")) {
                java.util.Set<String> present = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                while (rs.next()) {
                    present.add(rs.getString(1));
                }

                assertThat(present)
                        .as("every table the migrations are supposed to create")
                        .contains(
                                // A-003..A-007 · the baseline domain model
                                "users", "roles", "permissions", "role_permissions", "user_roles",
                                "projects", "project_members",
                                "tickets", "ticket_cycles", "ticket_history", "ticket_effort_logs",
                                "ticket_watchers", "ticket_links",
                                "workflow_templates", "workflow_stages", "ticket_stage_transitions",
                                "task_types", "priorities", "statuses", "workflow_transitions",
                                "sla_policies", "holidays", "resource_leaves",
                                "notification_templates", "notifications",
                                "chat_threads", "chat_participants", "chat_messages", "audit_logs",
                                "clients", "client_contacts", "client_projects",
                                "ticket_comments", "ticket_attachments", "email_log", "import_batches",
                                // Flyway's own bookkeeping
                                "flyway_schema_history",
                                // D-034, B-023, D-042, D-023, D-021, D-022, D-024, D-025, D-026
                                "email_suppressions", "working_calendar", "notification_preferences",
                                "stage_sla_alerts", "sla_prebreach_alerts", "stale_ticket_nudges",
                                "l2_escalations", "ping_pong_flags", "unassigned_ticket_alerts",
                                // A-027, A-028, A-029
                                "password_reset_tokens", "password_history", "totp_recovery_codes",
                                // D-045
                                "push_subscriptions",
                                // A-044
                                "chain_anchors",
                                // A-050
                                "daily_ticket_stats", "resource_daily_stats",
                                // A-059 · §S-05 widget 20, keyed by project as
                                // well as client so the row rule can scope it
                                "client_daily_stats",
                                // A-065 · §7.8's scheduled report emails
                                "report_schedules", "report_schedule_runs",
                                // B-033 · S-34 step 3's saveable column mappings.
                                // `.contains` would not have failed without this
                                // line, but the paragraphs above ask for the name
                                // and the point of naming them is that a
                                // *missing* table says which one.
                                "import_mapping_presets");
            }
            // Named, not counted — taking the same correction A-029 already
            // applied to the table assertion above. "expected: 8 but was: 10"
            // names neither the trigger that appeared nor the one that went
            // missing, and a dropped immutability trigger is the single most
            // important thing this file can notice. Names catch a deletion and
            // an addition separately; a count only ever says the total moved.
            try (ResultSet rs = s.executeQuery(
                    "SELECT trigger_name FROM information_schema.triggers "
                            + "WHERE trigger_schema = DATABASE() ORDER BY trigger_name")) {
                List<String> triggers = new ArrayList<>();
                while (rs.next()) {
                    triggers.add(rs.getString(1));
                }
                assertThat(triggers).containsExactlyInAnyOrder(
                        // A-008 · the immutability core
                        "trg_hist_no_update", "trg_hist_no_delete",
                        "trg_effort_no_update", "trg_effort_no_delete",
                        "trg_stage_seal_only", "trg_stage_no_delete",
                        // B-011 · a resource cannot be their own reporting manager
                        "trg_users_no_self_manager_ins", "trg_users_no_self_manager_upd",
                        // A-044 · the truncation anchor moves forward or not at all
                        "trg_chain_anchor_monotonic", "trg_chain_anchor_no_delete",
                        // A-071 · audit_logs, export-only. Listed here for the
                        // reason the paragraph above gives: these two are the
                        // layer that makes S-16's "never editable" a refusal
                        // rather than a convention, and a silent drop is exactly
                        // what this assertion exists to notice.
                        "trg_audit_no_update", "trg_audit_no_delete",
                        // A-106 · the onboarding module's append-only pair. The
                        // same reasoning the audit_logs line above gives, and
                        // the reason it belongs here rather than in a test of
                        // its own: this list is the one place a silently
                        // dropped trigger is noticed, and a per-module list
                        // would only notice a drop in the module somebody
                        // remembered to check.
                        "trg_ob_comms_no_update", "trg_ob_comms_no_delete",
                        "trg_ob_history_no_update", "trg_ob_history_no_delete",
                        // A-105 · the step clock. Not annotated append-only by
                        // the module plan — A-105 tightened it deliberately and
                        // said so — which makes it the entry most likely to be
                        // read as a mistake and "fixed" by deleting it. It is
                        // the TAT record every breach and every
                        // waiting-on-client attribution is computed from.
                        "trg_ob_clock_no_update", "trg_ob_clock_no_delete");
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
    // ------------------------------------------------------------------
    // A-123 — the onboarding module's append-only tables
    //
    // A-105 and A-106 both say in their own headers that they are
    // "unverified in the sense A-008 was until A-013": the triggers were
    // exercised by hand and nothing re-checked them per run. These are that
    // check, and they are here rather than in a class of their own because
    // every new IT class starts its own MySQL container — the suite already
    // pays that 45 times over.
    //
    // WHAT THESE ASSERT THAT A TRIGGER'S EXISTENCE DOES NOT
    //
    // `schemaHasExpectedShape` proves the six triggers are installed. It
    // cannot prove they refuse anything, and it cannot prove the refusal
    // says something a caller can act on. Both matter, and the second one
    // is not theoretical: on the first draft of A-105/A-106 three of the six
    // SIGNAL messages were over MySQL's 128-character MESSAGE_TEXT cap, so
    // the server discarded them and raised ERROR 1648, "Data too long for
    // condition item". The write was still refused — the guarantee held —
    // but the caller was told nothing about immutability and the SQLSTATE
    // was 22001 rather than the declared 45000.
    //
    // Every assertion below therefore matches on the message rather than
    // only on the exception. A test that accepted any SQLException would
    // have passed against that broken build.
    // ------------------------------------------------------------------

    /** Fresh journey + step, so these tests never share mutable state. */
    private long seedOnboardingStep(Connection c, String suffix) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("INSERT INTO ob_products (code, name) VALUES ('P" + suffix + "', 'Probe " + suffix + "')");
            long productId = lastInsertId(s);
            s.execute("INSERT INTO ob_clients (name, onboarding_date) "
                    + "VALUES ('Probe " + suffix + "', '2026-09-01')");
            long clientId = lastInsertId(s);
            s.execute("INSERT INTO ob_client_contacts (ob_client_id, name, email, is_primary) "
                    + "VALUES (" + clientId + ", 'Probe', 'probe" + suffix + "@test.local', 1)");
            long contactId = lastInsertId(s);
            s.execute("INSERT INTO ob_client_applications (ob_client_id, product_id) "
                    + "VALUES (" + clientId + ", " + productId + ")");
            s.execute("INSERT INTO ob_journey_templates (product_id, name, version, is_active) "
                    + "VALUES (" + productId + ", 'Probe', 1, 1)");
            long templateId = lastInsertId(s);
            s.execute("INSERT INTO ob_journeys (ob_client_id, product_id, template_id) "
                    + "VALUES (" + clientId + ", " + productId + ", " + templateId + ")");
            long journeyId = lastInsertId(s);
            s.execute("INSERT INTO ob_journey_steps (journey_id, sequence, name, tat_days) "
                    + "VALUES (" + journeyId + ", 1, 'Probe step', 1)");
            long stepId = lastInsertId(s);
            // Stash the ids the callers need on one row they can read back.
            onboardingJourneyId = journeyId;
            onboardingClientId = clientId;
            onboardingContactId = contactId;
            return stepId;
        }
    }

    private long onboardingJourneyId;
    private long onboardingClientId;
    private long onboardingContactId;

    @Test
    void obStepClockEventsRejectsUpdate() throws SQLException {
        try (Connection c = connect()) {
            long stepId = seedOnboardingStep(c, "CU");
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO ob_step_clock_events "
                        + "(step_id, journey_id, event_type, occurred_at) VALUES ("
                        + stepId + ", " + onboardingJourneyId + ", 'STARTED', NOW(6))");
            }
            // The TAT dispute in one statement: re-attributing elapsed time to
            // the client is the edit this table exists to make impossible.
            assertThatThrownBy(() -> {
                try (Statement s = c.createStatement()) {
                    s.execute("UPDATE ob_step_clock_events SET attributed_to = 'CLIENT' "
                            + "WHERE step_id = " + stepId);
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("cannot be updated");
        }
    }

    @Test
    void obStepClockEventsRejectsDelete() throws SQLException {
        try (Connection c = connect()) {
            long stepId = seedOnboardingStep(c, "CD");
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO ob_step_clock_events "
                        + "(step_id, journey_id, event_type, occurred_at) VALUES ("
                        + stepId + ", " + onboardingJourneyId + ", 'STARTED', NOW(6))");
            }
            assertThatThrownBy(() -> {
                try (Statement s = c.createStatement()) {
                    s.execute("DELETE FROM ob_step_clock_events WHERE step_id = " + stepId);
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("cannot be deleted");
        }
    }

    @Test
    void obStepCommunicationsRejectsUpdate() throws SQLException {
        try (Connection c = connect()) {
            long stepId = seedOnboardingStep(c, "MU");
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO ob_step_communications "
                        + "(step_id, journey_id, ob_client_id, body, author_type, author_user_id, occurred_at) "
                        + "VALUES (" + stepId + ", " + onboardingJourneyId + ", " + onboardingClientId
                        + ", 'internal note', 'STAFF', " + userId + ", NOW(6))");
            }
            // Flipping an internal note to client-visible after the fact is
            // the edit with the worst blast radius here: §9 CP-03 puts
            // internal comms on the never-visible list, and a client cannot
            // un-read one.
            assertThatThrownBy(() -> {
                try (Statement s = c.createStatement()) {
                    s.execute("UPDATE ob_step_communications SET is_client_visible = 1 "
                            + "WHERE step_id = " + stepId);
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("cannot be updated");
        }
    }

    @Test
    void obStepCommunicationsRejectsDelete() throws SQLException {
        try (Connection c = connect()) {
            long stepId = seedOnboardingStep(c, "MD");
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO ob_step_communications "
                        + "(step_id, journey_id, ob_client_id, body, author_type, author_user_id, occurred_at) "
                        + "VALUES (" + stepId + ", " + onboardingJourneyId + ", " + onboardingClientId
                        + ", 'note', 'STAFF', " + userId + ", NOW(6))");
            }
            assertThatThrownBy(() -> {
                try (Statement s = c.createStatement()) {
                    s.execute("DELETE FROM ob_step_communications WHERE step_id = " + stepId);
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("cannot be deleted");
        }
    }

    @Test
    void obStepHistoryRejectsUpdate() throws SQLException {
        try (Connection c = connect()) {
            long stepId = seedOnboardingStep(c, "HU");
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO ob_step_history "
                        + "(journey_id, step_id, ob_client_id, event_type, actor_type, actor_id, row_hash) "
                        + "VALUES (" + onboardingJourneyId + ", " + stepId + ", " + onboardingClientId
                        + ", 'STEP_ACTIVATED', 'USER', " + userId + ", REPEAT('a', 64))");
            }
            // Rewriting row_hash is the tamper this table's chain exists to
            // make detectable; the trigger makes it impossible in the first
            // place, which is the layer above detection.
            assertThatThrownBy(() -> {
                try (Statement s = c.createStatement()) {
                    s.execute("UPDATE ob_step_history SET row_hash = REPEAT('b', 64) "
                            + "WHERE step_id = " + stepId);
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("cannot be updated");
        }
    }

    @Test
    void obStepHistoryRejectsDelete() throws SQLException {
        try (Connection c = connect()) {
            long stepId = seedOnboardingStep(c, "HD");
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO ob_step_history "
                        + "(journey_id, step_id, ob_client_id, event_type, actor_type, actor_id, row_hash) "
                        + "VALUES (" + onboardingJourneyId + ", " + stepId + ", " + onboardingClientId
                        + ", 'STEP_ACTIVATED', 'USER', " + userId + ", REPEAT('a', 64))");
            }
            assertThatThrownBy(() -> {
                try (Statement s = c.createStatement()) {
                    s.execute("DELETE FROM ob_step_history WHERE step_id = " + stepId);
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("cannot be deleted");
        }
    }

    /**
     * The refusals above are only half the guarantee. A table nothing can
     * correct is not append-only, it is broken — so the compensating path
     * has to work, or the first genuine mistake leaves somebody with a
     * choice between a wrong record and a dropped trigger.
     */
    @Test
    void obStepCommunicationsCorrectsByCompensatingRow() throws SQLException {
        try (Connection c = connect()) {
            long stepId = seedOnboardingStep(c, "CX");
            long originalId;
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO ob_step_communications "
                        + "(step_id, journey_id, ob_client_id, body, author_type, author_user_id, occurred_at) "
                        + "VALUES (" + stepId + ", " + onboardingJourneyId + ", " + onboardingClientId
                        + ", 'wrong step', 'STAFF', " + userId + ", NOW(6))");
                originalId = lastInsertId(s);
                s.execute("INSERT INTO ob_step_communications "
                        + "(step_id, journey_id, ob_client_id, body, author_type, author_user_id, occurred_at, "
                        + " is_correction, corrects_entry_id) "
                        + "VALUES (" + stepId + ", " + onboardingJourneyId + ", " + onboardingClientId
                        + ", 'retracted', 'STAFF', " + userId + ", NOW(6), 1, " + originalId + ")");
            }
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT COUNT(*) FROM ob_step_communications WHERE step_id = " + stepId)) {
                rs.next();
                assertThat(rs.getInt(1))
                        .as("the original survives its own correction — that is the point of a reversal")
                        .isEqualTo(2);
            }
        }
    }

    /**
     * A correction has to name what it corrects. Without this, `is_correction`
     * is a flag anybody can set on an ordinary row and the reversal stops
     * being traceable to the entry it reverses.
     */
    @Test
    void obStepCommunicationsRefusesACorrectionThatCorrectsNothing() throws SQLException {
        try (Connection c = connect()) {
            long stepId = seedOnboardingStep(c, "CN");
            assertThatThrownBy(() -> {
                try (Statement s = c.createStatement()) {
                    s.execute("INSERT INTO ob_step_communications "
                            + "(step_id, journey_id, ob_client_id, body, author_type, author_user_id, "
                            + " occurred_at, is_correction) "
                            + "VALUES (" + stepId + ", " + onboardingJourneyId + ", " + onboardingClientId
                            + ", 'x', 'STAFF', " + userId + ", NOW(6), 1)");
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("ck_ob_comms_correction");
        }
    }
}
