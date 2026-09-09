package com.edunext.edutrack.api.feature.onboarding.instances;

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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-123 · the service-level dependency's hold and release, against real
 * MySQL. Earns a container on {@code ObPrerequisiteGateServiceIT}'s own
 * reasoning one class over: {@link ObJourneyDependencyRelease} is a guarded
 * {@code UPDATE} plus a cross-journey activation that reads the row it just
 * wrote, and the property worth proving — that completing the dependency
 * releases the held journey, activates its first wave and queues the
 * kickoff, all inside the one transaction the completing step's own
 * {@code complete()} call opened — cannot be asserted against a mocked
 * {@code JdbcClient}.
 *
 * <p>Both journeys' gates are opened by a direct {@code UPDATE} in {@link
 * #seed()} rather than through {@link ObPrerequisiteGateService}: the gate
 * is that task's own subject, proven there, and pulling B-125's whole
 * prerequisite fixture into this test would prove C-118 a second time
 * instead of C-123.
 */
@SpringBootTest
@Testcontainers
class ObJourneyDependencyReleaseIT {

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
    ObJourneyInstantiationService instantiation;

    @Autowired
    ObJourneyStepLifecycleService lifecycle;

    @Autowired
    JdbcTemplate jdbc;

    private long dependencyOwner;
    private long dependentOwner;
    private long client;
    private long dependencyProduct;
    private long dependentProduct;
    private long dependencyStepId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM ob_notification_outbox WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT Dep %')");
        jdbc.update("DELETE FROM ob_journey_steps WHERE journey_id IN "
                + "(SELECT id FROM ob_journeys WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT Dep %'))");
        jdbc.update("DELETE FROM ob_journeys WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT Dep %')");
        jdbc.update("DELETE FROM ob_client_applications WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT Dep %')");
        jdbc.update("DELETE FROM ob_client_contacts WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT Dep %')");
        jdbc.update("DELETE FROM ob_clients WHERE name LIKE 'IT Dep %'");
        jdbc.update("DELETE FROM ob_journey_template_steps WHERE template_id IN "
                + "(SELECT id FROM ob_journey_templates WHERE name LIKE 'IT Dep %')");
        jdbc.update("DELETE FROM ob_journey_templates WHERE name LIKE 'IT Dep %'");
        jdbc.update("DELETE FROM ob_products WHERE code LIKE 'IT_DEP_%'");
        jdbc.update("DELETE FROM users WHERE username LIKE 'it_dep_%'");

        Long roleId = jdbc.queryForObject("SELECT id FROM roles ORDER BY id LIMIT 1", Long.class);
        dependencyOwner = insertUser(roleId, "it_dep_erp_owner");
        dependentOwner = insertUser(roleId, "it_dep_bio_owner");

        jdbc.update("INSERT INTO ob_products (code, name, is_active) VALUES ('IT_DEP_ERP', 'IT Dep ERP', 1)");
        dependencyProduct = lastInsertId();
        jdbc.update("""
                INSERT INTO ob_journey_templates (product_id, name, version, is_active, sequence)
                VALUES (?, 'IT Dep ERP Onboarding', 1, 1, 0)
                """, dependencyProduct);
        long dependencyTemplate = lastInsertId();
        jdbc.update("""
                INSERT INTO ob_journey_template_steps (template_id, sequence, name, tat_days, owner_user_id)
                VALUES (?, 1, 'ERP Rollout', 2, ?)
                """, dependencyTemplate, dependencyOwner);

        jdbc.update("""
                INSERT INTO ob_products (code, name, is_active) VALUES ('IT_DEP_BIO', 'IT Dep Biometric', 1)
                """);
        dependentProduct = lastInsertId();
        jdbc.update("""
                INSERT INTO ob_journey_templates
                    (product_id, name, version, is_active, sequence, depends_on_template_id)
                VALUES (?, 'IT Dep Biometric Onboarding', 1, 1, 1, ?)
                """, dependentProduct, dependencyTemplate);
        long dependentTemplate = lastInsertId();
        jdbc.update("""
                INSERT INTO ob_journey_template_steps (template_id, sequence, name, tat_days, owner_user_id)
                VALUES (?, 1, 'Device Rollout', 2, ?)
                """, dependentTemplate, dependentOwner);

        jdbc.update("INSERT INTO ob_clients (name, onboarding_date) VALUES ('IT Dep Client', ?)",
                LocalDate.of(2026, 9, 7));
        client = lastInsertId();
        jdbc.update("""
                INSERT INTO ob_client_applications
                    (ob_client_id, product_id, license_type, units, license_start, license_end)
                VALUES (?, ?, 'ANNUAL', 100, ?, ?), (?, ?, 'ANNUAL', 100, ?, ?)
                """, client, dependencyProduct, LocalDate.of(2026, 9, 7), LocalDate.of(2027, 9, 7),
                client, dependentProduct, LocalDate.of(2026, 9, 7), LocalDate.of(2027, 9, 7));

        // Both products bought together, dependency first — instantiateAll's
        // own sequencing is C-123's own unit-tested subject, not this IT's.
        instantiation.instantiateAll(client, List.of(dependencyProduct, dependentProduct));

        // The gate is C-118's own subject — opened here by a direct write
        // so this test can drive the dependency mechanism on its own.
        jdbc.update("""
                UPDATE ob_journeys SET gate_status = 'OPEN', gate_opened_at = NOW(6)
                 WHERE ob_client_id = ?
                """, client);
        lifecycle.activateEligibleSteps(dependencyJourneyId());
        lifecycle.activateEligibleSteps(dependentJourneyId());

        dependencyStepId = jdbc.queryForObject("""
                SELECT s.id FROM ob_journey_steps s WHERE s.journey_id = ?
                """, Long.class, dependencyJourneyId());
    }

    @Test
    @DisplayName("instantiation holds the dependent journey behind the one it depends on")
    void instantiationHoldsTheDependentJourney() {
        assertThat(heldBy(dependentJourneyId())).isEqualTo(dependencyJourneyId());
        assertThat(heldBy(dependencyJourneyId())).isNull();
        // Held, so opening the gate alone does not start its step — the
        // seed()'s own activateEligibleSteps call above already proved this
        // a no-op for it.
        assertThat(stepStatusOf(dependentJourneyId())).isEqualTo("PENDING");
        assertThat(stepStatusOf(dependencyJourneyId())).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("completing the dependency releases the held journey, activates it, and queues the unblock mail")
    void completingTheDependencyReleasesAndActivates() {
        lifecycle.complete(dependencyStepId, dependencyOwner);

        assertThat(heldBy(dependentJourneyId())).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ob_journeys WHERE id = ? AND released_at IS NOT NULL
                """, Integer.class, dependentJourneyId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ob_journeys WHERE id = ? AND completed_at IS NOT NULL
                """, Integer.class, dependencyJourneyId())).isEqualTo(1);

        assertThat(stepStatusOf(dependentJourneyId())).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ob_journey_steps s
                 WHERE s.journey_id = ? AND s.status = 'IN_PROGRESS' AND s.due_at IS NOT NULL
                """, Integer.class, dependentJourneyId())).isEqualTo(1);

        List<Map<String, Object>> queued = jdbc.queryForList("""
                SELECT channel, recipient_user_id FROM ob_notification_outbox
                 WHERE ob_client_id = ? AND event_key = 'JOURNEY_UNBLOCKED'
                 ORDER BY channel
                """, client);
        assertThat(queued).hasSize(2);
        assertThat(queued).extracting(r -> ((Number) r.get("recipient_user_id")).longValue())
                .containsOnly(dependentOwner);
    }

    private Long heldBy(long journeyId) {
        return jdbc.queryForObject(
                "SELECT held_by_journey_id FROM ob_journeys WHERE id = ?", Long.class, journeyId);
    }

    private String stepStatusOf(long journeyId) {
        return jdbc.queryForObject(
                "SELECT status FROM ob_journey_steps WHERE journey_id = ?", String.class, journeyId);
    }

    private long dependencyJourneyId() {
        return jdbc.queryForObject(
                "SELECT id FROM ob_journeys WHERE ob_client_id = ? AND product_id = ?",
                Long.class, client, dependencyProduct);
    }

    private long dependentJourneyId() {
        return jdbc.queryForObject(
                "SELECT id FROM ob_journeys WHERE ob_client_id = ? AND product_id = ?",
                Long.class, client, dependentProduct);
    }

    private long insertUser(Long roleId, String username) {
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?)
                """, username, username, username + "@example.com", username, roleId);
        return lastInsertId();
    }

    private long lastInsertId() {
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }
}
