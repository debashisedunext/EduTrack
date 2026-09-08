package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.api.feature.onboarding.prereqs.ObPrereqGate;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqTask;
import com.edunext.edutrack.domain.onboarding.ObGateStatus;
import com.edunext.edutrack.domain.onboarding.ObPrereqTaskStatus;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-118 · the real gate against real MySQL.
 *
 * <p>Earns a container for the reason {@code ObTatScannerIT} did: the class
 * under test is a guarded {@code UPDATE} plus a step activation that reads
 * the row it just wrote, and the property worth proving — that all four of
 * plan §5.3's consequences commit together, and commit once — cannot be
 * asserted against a mocked {@code JdbcClient}.
 *
 * <p>The gate is driven directly, with the task list the contract says an
 * empty checklist satisfies, rather than through {@code
 * ObPrereqTaskService#verify}: that route's own unit test already proves it
 * calls {@code evaluate} with the full task set, and repeating B-125's
 * whole prerequisite fixture here would test B-125's instantiation a second
 * time rather than this task's flip.
 */
@SpringBootTest
@Testcontainers
class ObPrerequisiteGateServiceIT {

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
    ObPrereqGate gate;

    @Autowired
    ObJourneyInstantiationService instantiation;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    private long owner;
    private long client;
    private long product;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM ob_notification_outbox WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT Gate %')");
        jdbc.update("DELETE FROM ob_journey_steps WHERE journey_id IN "
                + "(SELECT id FROM ob_journeys WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT Gate %'))");
        jdbc.update("DELETE FROM ob_journeys WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT Gate %')");
        jdbc.update("DELETE FROM ob_client_applications WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT Gate %')");
        jdbc.update("DELETE FROM ob_client_contacts WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT Gate %')");
        jdbc.update("DELETE FROM ob_clients WHERE name LIKE 'IT Gate %'");
        jdbc.update("DELETE FROM ob_journey_template_steps WHERE template_id IN "
                + "(SELECT id FROM ob_journey_templates WHERE name LIKE 'IT Gate %')");
        jdbc.update("DELETE FROM ob_journey_templates WHERE name LIKE 'IT Gate %'");
        jdbc.update("DELETE FROM ob_products WHERE code LIKE 'IT_GATE_%'");
        jdbc.update("DELETE FROM users WHERE username LIKE 'it_gate_%'");

        Long roleId = jdbc.queryForObject("SELECT id FROM roles ORDER BY id LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES ('it_gate_owner', 'it_gate_owner', 'it_gate_owner@example.com',
                        'not-a-real-hash', 'IT Gate Owner', ?)
                """, roleId);
        owner = lastInsertId();

        jdbc.update("INSERT INTO ob_products (code, name, is_active) VALUES ('IT_GATE_ERP', 'IT Gate ERP', 1)");
        product = lastInsertId();

        jdbc.update("""
                INSERT INTO ob_journey_templates (product_id, name, version, is_active, sequence)
                VALUES (?, 'IT Gate ERP Onboarding', 1, 1, 0)
                """, product);
        long template = lastInsertId();
        // Two steps: Kickoff runs from the start and is owned; Configuration
        // waits on Kickoff. The gate must activate exactly the first.
        jdbc.update("""
                INSERT INTO ob_journey_template_steps (template_id, sequence, name, tat_days, owner_user_id)
                VALUES (?, 1, 'Kickoff', 2, ?)
                """, template, owner);
        long kickoff = lastInsertId();
        jdbc.update("""
                INSERT INTO ob_journey_template_steps
                    (template_id, sequence, name, tat_days, depends_on_step_id)
                VALUES (?, 2, 'Configuration', 3, ?)
                """, template, kickoff);

        jdbc.update("INSERT INTO ob_clients (name, onboarding_date) VALUES ('IT Gate Client', ?)",
                LocalDate.of(2026, 9, 7));
        client = lastInsertId();
        jdbc.update("""
                INSERT INTO ob_client_contacts (ob_client_id, name, designation, email, phone, is_primary)
                VALUES (?, 'IT Gate SPOC', 'Principal', 'it.gate.spoc@example.com', '+911234567890', 1)
                """, client);
        jdbc.update("""
                INSERT INTO ob_client_applications
                    (ob_client_id, product_id, license_type, units, license_start, license_end)
                VALUES (?, ?, 'ANNUAL', 100, ?, ?)
                """, client, product, LocalDate.of(2026, 9, 7), LocalDate.of(2027, 9, 7));

        instantiation.instantiate(client, product);
    }

    @Test
    @DisplayName("a satisfied gate opens every locked journey, activates the first wave and queues the kickoff")
    void aSatisfiedGateOpensEverything() {
        assertThat(gateStatus()).isEqualTo("LOCKED");
        assertThat(stepStatuses()).containsExactly("PENDING", "PENDING");

        ObPrereqGate.Outcome outcome = evaluate();

        assertThat(outcome.gateStatus()).isEqualTo(ObGateStatus.OPEN);
        assertThat(outcome.gateOpened()).isTrue();
        assertThat(outcome.openedJourneyIds()).hasSize(1);

        // The flip, with the timestamp the CHECK constraint insists on.
        assertThat(gateStatus()).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ob_journeys WHERE ob_client_id = ? AND gate_opened_at IS NULL",
                Integer.class, client)).isZero();

        // The first wave: the dependency-free step running with a clock, the
        // dependent one still waiting on it.
        assertThat(stepStatuses()).containsExactly("IN_PROGRESS", "PENDING");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ob_journey_steps s
                  JOIN ob_journeys j ON j.id = s.journey_id
                 WHERE j.ob_client_id = ? AND s.status = 'IN_PROGRESS' AND s.due_at IS NOT NULL
                """, Integer.class, client)).isEqualTo(1);

        // The kickoff: one mail to the SPOC, a mail and a bell entry to the
        // Kickoff step's owner, and nobody else.
        List<Map<String, Object>> queued = jdbc.queryForList("""
                SELECT recipient_type, channel, recipient_user_id, recipient_contact_id
                  FROM ob_notification_outbox
                 WHERE ob_client_id = ? AND event_key = 'GATE_OPENED'
                 ORDER BY recipient_type, channel
                """, client);
        assertThat(queued).hasSize(3);
        assertThat(queued).filteredOn(r -> "CLIENT".equals(r.get("recipient_type"))).hasSize(1);
        assertThat(queued).filteredOn(r -> "STAFF".equals(r.get("recipient_type")))
                .extracting(r -> ((Number) r.get("recipient_user_id")).longValue())
                .containsOnly(owner);
    }

    @Test
    @DisplayName("an unsatisfied gate flips nothing and says so")
    void anUnsatisfiedGateFlipsNothing() {
        ObPrereqGate.Outcome outcome = new TransactionTemplate(txManager).execute(status ->
                gate.evaluate(client, List.of(holdingTask())));

        assertThat(outcome.gateStatus()).isEqualTo(ObGateStatus.LOCKED);
        assertThat(outcome.gateOpened()).isFalse();
        assertThat(outcome.openedJourneyIds()).isEmpty();
        assertThat(gateStatus()).isEqualTo("LOCKED");
        assertThat(stepStatuses()).containsExactly("PENDING", "PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ob_notification_outbox WHERE ob_client_id = ?",
                Integer.class, client)).isZero();
    }

    @Test
    @DisplayName("a second evaluation after the gate opened reports open, but opens nothing again")
    void theGateOpensOnce() {
        evaluate();
        int queuedAfterFirst = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ob_notification_outbox WHERE ob_client_id = ?", Integer.class, client);

        ObPrereqGate.Outcome again = evaluate();

        assertThat(again.gateStatus()).isEqualTo(ObGateStatus.OPEN);
        assertThat(again.gateOpened()).isFalse();
        assertThat(again.openedJourneyIds()).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ob_notification_outbox WHERE ob_client_id = ?", Integer.class, client))
                .isEqualTo(queuedAfterFirst);
    }

    /** {@code evaluate} is {@code MANDATORY}; the caller's transaction is what this stands in for. */
    private ObPrereqGate.Outcome evaluate() {
        return new TransactionTemplate(txManager).execute(status -> gate.evaluate(client, List.of()));
    }

    /** A mandatory task still PENDING — the one shape that certainly holds the gate. */
    private static ObClientPrereqTask holdingTask() {
        ObClientPrereqTask task = new ObClientPrereqTask();
        task.setMandatory(true);
        task.setStatus(ObPrereqTaskStatus.PENDING);
        return task;
    }

    private String gateStatus() {
        return jdbc.queryForObject(
                "SELECT gate_status FROM ob_journeys WHERE ob_client_id = ?", String.class, client);
    }

    private List<String> stepStatuses() {
        return jdbc.queryForList("""
                SELECT s.status FROM ob_journey_steps s
                  JOIN ob_journeys j ON j.id = s.journey_id
                 WHERE j.ob_client_id = ?
                 ORDER BY s.sequence
                """, String.class, client);
    }

    private long lastInsertId() {
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }
}
