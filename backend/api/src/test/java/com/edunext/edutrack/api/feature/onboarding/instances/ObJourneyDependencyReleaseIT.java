package com.edunext.edutrack.api.feature.onboarding.instances;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
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

    /**
     * Only for the seed's own activation calls. {@code activateEligibleSteps}
     * is not itself {@code @Transactional} — in production it is always
     * reached from inside {@code complete()} or {@code skip()}, which are —
     * so called bare from a test it loads its entities through repository
     * methods that end their own transaction on return, leaves them detached,
     * and drops every status change it makes on the floor.
     *
     * <p>That is not a bug in the service; it is this test calling an
     * internal collaborator directly, which only the seed does. Wrapping the
     * calls in one transaction gives them the surrounding unit of work
     * production always supplies, so the seed leaves the steps it says it
     * leaves.
     */
    @Autowired
    TransactionTemplate transactions;

    private long dependencyOwner;
    private long dependentOwner;
    private long client;
    private long dependencyProduct;
    private long secondDependencyProduct;
    private long dependentProduct;
    private long dependencyStepId;
    private long secondDependencyStepId;

    /**
     * Makes every row this test writes unique to one run of one test method.
     *
     * <p>This used to open by deleting the previous run's fixture, which stopped
     * being possible: a lifecycle transition now writes an {@code ob_step_history}
     * row, that table is append-only and hash-chained, and its trigger refuses a
     * {@code DELETE} with "deleting breaks the hash chain for its journey" — so
     * the steps those rows point at cannot be deleted either, and the second
     * test method of the class failed in {@code seed()} rather than in anything
     * it was asserting.
     *
     * <p>Not worked around by exempting the journal. Refusing that delete is the
     * guarantee, not an obstacle to it — CLAUDE.md's append-only rule is explicit
     * that a correction is a new row and never a mutation, and a test that
     * reaches for a way round the trigger is a test teaching the next reader that
     * there is one. Unique names cost one column per insert and leave the
     * guarantee alone; the rows accumulate inside a container that is thrown away
     * when the class finishes.
     */
    private String tag;

    @BeforeEach
    void seed() {
        // Six hex digits of the clock: unique enough within one container's
        // life, and short enough that `users.emp_code` — VARCHAR(20) — still
        // fits the prefixed form below.
        String clock = Long.toHexString(System.nanoTime());
        tag = clock.substring(clock.length() - 6);
        Long roleId = jdbc.queryForObject("SELECT id FROM roles ORDER BY id LIMIT 1", Long.class);
        dependencyOwner = insertUser(roleId, "it_dep_erp_owner_" + tag);
        dependentOwner = insertUser(roleId, "it_dep_bio_owner_" + tag);

        jdbc.update("INSERT INTO ob_products (code, name, is_active) VALUES (?, ?, 1)",
                "IT_DEP_ERP_" + tag, "IT Dep ERP " + tag);
        dependencyProduct = productId("IT_DEP_ERP_" + tag);
        jdbc.update("""
                INSERT INTO ob_journey_templates (product_id, name, version, is_active, sequence)
                VALUES (?, ?, 1, 1, 0)
                """, dependencyProduct, "IT Dep ERP Onboarding " + tag);
        long dependencyTemplate = templateId("IT Dep ERP Onboarding " + tag);
        jdbc.update("""
                INSERT INTO ob_journey_template_steps (template_id, sequence, name, tat_days, owner_user_id)
                VALUES (?, 1, 'ERP Rollout', 2, ?)
                """, dependencyTemplate, dependencyOwner);

        /*
          A *second* service the dependent waits behind, so this IT exercises
          the set rather than the one-dependency case. It is the case the
          release code is most easily got wrong on: clearing every journey
          whose held_by_journey_id matches would start this journey the moment
          the first of its two dependencies finished.
        */
        jdbc.update("INSERT INTO ob_products (code, name, is_active) VALUES (?, ?, 1)",
                "IT_DEP_NET_" + tag, "IT Dep Network " + tag);
        secondDependencyProduct = productId("IT_DEP_NET_" + tag);
        jdbc.update("""
                INSERT INTO ob_journey_templates (product_id, name, version, is_active, sequence)
                VALUES (?, ?, 1, 1, 1)
                """, secondDependencyProduct, "IT Dep Network Survey " + tag);
        long secondDependencyTemplate = templateId("IT Dep Network Survey " + tag);
        jdbc.update("""
                INSERT INTO ob_journey_template_steps (template_id, sequence, name, tat_days, owner_user_id)
                VALUES (?, 1, 'Site Survey', 2, ?)
                """, secondDependencyTemplate, dependencyOwner);

        jdbc.update("INSERT INTO ob_products (code, name, is_active) VALUES (?, ?, 1)",
                "IT_DEP_BIO_" + tag, "IT Dep Biometric " + tag);
        dependentProduct = productId("IT_DEP_BIO_" + tag);
        jdbc.update("""
                INSERT INTO ob_journey_templates
                    (product_id, name, version, is_active, sequence)
                VALUES (?, ?, 1, 1, 2)
                """, dependentProduct, "IT Dep Biometric Onboarding " + tag);
        long dependentTemplate = templateId("IT Dep Biometric Onboarding " + tag);
        // The declaration itself — rows rather than a column, since
        // V20260911_1100.
        jdbc.update("""
                INSERT INTO ob_journey_template_dependencies (template_id, depends_on_template_id)
                VALUES (?, ?), (?, ?)
                """, dependentTemplate, dependencyTemplate, dependentTemplate, secondDependencyTemplate);
        jdbc.update("""
                INSERT INTO ob_journey_template_steps (template_id, sequence, name, tat_days, owner_user_id)
                VALUES (?, 1, 'Device Rollout', 2, ?)
                """, dependentTemplate, dependentOwner);

        jdbc.update("INSERT INTO ob_clients (name, onboarding_date) VALUES (?, ?)",
                "IT Dep Client " + tag, LocalDate.of(2026, 9, 7));
        client = jdbc.queryForObject(
                "SELECT id FROM ob_clients WHERE name = ?", Long.class, "IT Dep Client " + tag);
        jdbc.update("""
                INSERT INTO ob_client_applications
                    (ob_client_id, product_id, license_type, units, license_start, license_end)
                VALUES (?, ?, 'ANNUAL', 100, ?, ?), (?, ?, 'ANNUAL', 100, ?, ?),
                       (?, ?, 'ANNUAL', 100, ?, ?)
                """, client, dependencyProduct, LocalDate.of(2026, 9, 7), LocalDate.of(2027, 9, 7),
                client, secondDependencyProduct, LocalDate.of(2026, 9, 7), LocalDate.of(2027, 9, 7),
                client, dependentProduct, LocalDate.of(2026, 9, 7), LocalDate.of(2027, 9, 7));

        // All three bought together, dependencies first — instantiateAll's
        // own sequencing is C-123's own unit-tested subject, not this IT's.
        instantiation.instantiateAll(
                client, List.of(dependencyProduct, secondDependencyProduct, dependentProduct));

        // The gate is C-118's own subject — opened here by a direct write
        // so this test can drive the dependency mechanism on its own.
        jdbc.update("""
                UPDATE ob_journeys SET gate_status = 'OPEN', gate_opened_at = NOW(6)
                 WHERE ob_client_id = ?
                """, client);
        transactions.executeWithoutResult(status -> {
            lifecycle.activateEligibleSteps(dependencyJourneyId());
            lifecycle.activateEligibleSteps(secondDependencyJourneyId());
            lifecycle.activateEligibleSteps(dependentJourneyId());
        });

        dependencyStepId = stepOf(dependencyJourneyId());
        secondDependencyStepId = stepOf(secondDependencyJourneyId());
    }

    @Test
    @DisplayName("instantiation holds the dependent journey behind the first of the two it depends on")
    void instantiationHoldsTheDependentJourney() {
        // The lower journey id, because `held_by_journey_id` is a cursor over
        // the set rather than the set — see ObJourneyDependencyRelease.
        assertThat(heldBy(dependentJourneyId())).isEqualTo(dependencyJourneyId());
        assertThat(heldBy(dependencyJourneyId())).isNull();
        assertThat(heldBy(secondDependencyJourneyId())).isNull();
        // Held, so opening the gate alone does not start its step — the
        // seed()'s own activateEligibleSteps call above already proved this
        // a no-op for it.
        assertThat(stepStatusOf(dependentJourneyId())).isEqualTo("PENDING");
        assertThat(stepStatusOf(dependencyJourneyId())).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("completing only the first dependency re-points the hold rather than releasing it")
    void firstOfTwoDependenciesDoesNotRelease() {
        lifecycle.complete(dependencyStepId, dependencyOwner);

        // Still held — by the other one, which is still running.
        assertThat(heldBy(dependentJourneyId())).isEqualTo(secondDependencyJourneyId());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ob_journeys WHERE id = ? AND released_at IS NULL
                """, Integer.class, dependentJourneyId())).isEqualTo(1);
        // And no step of it has started, which is the fact the hold exists for.
        assertThat(stepStatusOf(dependentJourneyId())).isEqualTo("PENDING");
        // Nor has anybody been told it is unblocked, because it is not.
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ob_notification_outbox
                 WHERE ob_client_id = ? AND event_key = 'JOURNEY_UNBLOCKED'
                """, Integer.class, client)).isZero();
    }

    @Test
    @DisplayName("completing the last dependency releases the held journey, activates it, and queues the unblock mail")
    void completingTheDependencyReleasesAndActivates() {
        lifecycle.complete(dependencyStepId, dependencyOwner);
        lifecycle.complete(secondDependencyStepId, dependencyOwner);

        assertThat(heldBy(dependentJourneyId())).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ob_journeys WHERE id = ? AND released_at IS NOT NULL
                """, Integer.class, dependentJourneyId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ob_journeys WHERE id IN (?, ?) AND completed_at IS NOT NULL
                """, Integer.class, dependencyJourneyId(), secondDependencyJourneyId())).isEqualTo(2);

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

    private long secondDependencyJourneyId() {
        return jdbc.queryForObject(
                "SELECT id FROM ob_journeys WHERE ob_client_id = ? AND product_id = ?",
                Long.class, client, secondDependencyProduct);
    }

    private long stepOf(long journeyId) {
        return jdbc.queryForObject(
                "SELECT id FROM ob_journey_steps WHERE journey_id = ?", Long.class, journeyId);
    }

    private long dependentJourneyId() {
        return jdbc.queryForObject(
                "SELECT id FROM ob_journeys WHERE ob_client_id = ? AND product_id = ?",
                Long.class, client, dependentProduct);
    }

    private long insertUser(Long roleId, String username) {
        // emp_code is VARCHAR(20) and unique, so it gets its own short form
        // rather than the username, which carries the whole tagged name.
        String empCode = ("IT" + tag + username.charAt(7)).toUpperCase(java.util.Locale.ROOT);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?)
                """, empCode, username, username + "@example.com", username, roleId);
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    /*
      Every id above is read back by its own natural key rather than from
      `SELECT LAST_INSERT_ID()`, which this test used to use. That function is
      scoped to the *connection* that performed the insert, and a JdbcTemplate
      outside a transaction takes a connection from the pool per statement —
      so the id it returns is only right while the pool happens to hand back
      the same one. It did, with two products; adding a third was enough to
      break the coincidence, and it broke as an assertion about a step status
      three methods away rather than as anything naming an id.
    */
    private long productId(String code) {
        return jdbc.queryForObject("SELECT id FROM ob_products WHERE code = ?", Long.class, code);
    }

    private long templateId(String name) {
        return jdbc.queryForObject(
                "SELECT id FROM ob_journey_templates WHERE name = ?", Long.class, name);
    }
}
