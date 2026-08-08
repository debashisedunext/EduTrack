package com.edunext.edutrack.domain.seed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-008 · what the load order actually produced.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code SeedManifestTest} checks the register matches the directory. This
 * checks the register was <em>right</em> — that running those files in that
 * order left a coherent database rather than a plausible-looking one.
 *
 * <p>The gap it covers is specific. Several seed files reference each other
 * through <b>plain strings with no foreign key</b>: {@code
 * workflow_transitions.role_code}, {@code workflow_stages.owner_role} and the
 * status codes are all {@code VARCHAR}, deliberately (see the
 * {@code baseline_masters_ops.sql} DDL). A seed naming a role that does not
 * exist is therefore not a constraint violation — it inserts happily and simply
 * never matches a caller. No error, no log line, no failing test. The row is
 * just dead.
 *
 * <p>That is not a hypothetical failure mode; it is the one that shipped.
 * {@code V20260807_1030} renamed {@code SUPPORT_DESK} to {@code SUPPORT} and
 * {@code V20260807_1100}, running immediately after it, seeded the old value
 * into thirteen rows — silently disabling every status transition the Support
 * Desk role has, including the two the governance decisions reserve for it.
 * {@code V20260808_1400} repairs that, and
 * {@link #noTransitionReferencesARoleThatDoesNotExist()} is what stops it
 * recurring.
 *
 * <p>Container flags mirror {@code docker-compose.yml}, as in
 * {@code EntityMappingIT} — the point is a database migrated exactly the way a
 * real one is.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SeedDataIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_seed_it")
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

    @Autowired
    DataSource dataSource;

    JdbcClient db;

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(dataSource);
    }

    // ------------------------------------------------------------------
    // Referential integrity across the references MySQL is not enforcing
    // ------------------------------------------------------------------

    /**
     * The regression test for {@code V20260808_1400}. Fails with thirteen rows
     * if that migration is removed.
     */
    @Test
    void noTransitionReferencesARoleThatDoesNotExist() {
        List<String> orphans = db.sql("""
                        SELECT DISTINCT wt.role_code
                          FROM workflow_transitions wt
                          LEFT JOIN roles r ON r.code = wt.role_code
                         WHERE r.id IS NULL
                        """)
                .query(String.class).list();

        assertThat(orphans)
                .as("workflow_transitions is a whitelist — a row whose role_code matches "
                        + "no role silently forbids that move instead of allowing it")
                .isEmpty();
    }

    /**
     * The same shape one table over. A stage owned by a non-existent role means
     * nobody can ever act on that ribbon segment.
     */
    @Test
    void noStageIsOwnedByARoleThatDoesNotExist() {
        List<String> orphans = db.sql("""
                        SELECT DISTINCT ws.owner_role
                          FROM workflow_stages ws
                          LEFT JOIN roles r ON r.code = ws.owner_role
                         WHERE r.id IS NULL
                        """)
                .query(String.class).list();

        assertThat(orphans)
                .as("the ribbon matches the current stage owner against the caller's role code")
                .isEmpty();
    }

    /** {@code from_status IS NULL} is legal — it means "on creation". A wrong code is not. */
    @Test
    void everyTransitionMovesBetweenStatusesThatExist() {
        List<String> orphans = db.sql("""
                        SELECT DISTINCT wt.to_status
                          FROM workflow_transitions wt
                          LEFT JOIN statuses s ON s.code = wt.to_status
                         WHERE s.id IS NULL
                        UNION
                        SELECT DISTINCT wt.from_status
                          FROM workflow_transitions wt
                          LEFT JOIN statuses s ON s.code = wt.from_status
                         WHERE wt.from_status IS NOT NULL AND s.id IS NULL
                        """)
                .query(String.class).list();

        assertThat(orphans).as("status codes in the transition matrix resolve").isEmpty();
    }

    /**
     * Loop-back targets are a JSON array of stage codes, and they must resolve
     * <em>within the same template</em> — Infra Flow has no Development stage,
     * so the generic "return to DEV" of the blueprint's table had to be
     * rerouted there. A target naming a stage that template does not contain is
     * a rework path that dead-ends at runtime.
     */
    @Test
    void everyStageReturnTargetExistsInItsOwnTemplate() {
        List<String> dangling = db.sql("""
                        SELECT CONCAT(t.name, ': ', ws.stage_code, ' -> ', jt.target)
                          FROM workflow_stages ws
                          JOIN workflow_templates t ON t.id = ws.template_id
                          JOIN JSON_TABLE(ws.can_return_to, '$[*]'
                                 COLUMNS (target VARCHAR(20) PATH '$')) jt
                         WHERE NOT EXISTS (
                               SELECT 1 FROM workflow_stages peer
                                WHERE peer.template_id = ws.template_id
                                  AND peer.stage_code = jt.target)
                        """)
                .query(String.class).list();

        assertThat(dangling).as("every loop-back lands on a stage of the same template").isEmpty();
    }

    /** A task type defaulting to a level no priority defines pre-fills the create form with nothing. */
    @Test
    void everyTaskTypeDefaultsToALevelThatExists() {
        List<String> orphans = db.sql("""
                        SELECT tt.code
                          FROM task_types tt
                          LEFT JOIN priorities p ON p.code = tt.default_level
                         WHERE p.id IS NULL
                        """)
                .query(String.class).list();

        assertThat(orphans).as("default_level resolves against the priority master").isEmpty();
    }

    // ------------------------------------------------------------------
    // The decisions that are data rather than code
    // ------------------------------------------------------------------

    /**
     * G-3 (PLAN.md §5): closure belongs to the Sign-off stage owner. Expressed
     * as data — the absence of a row — so it is invisible in code review and
     * only a test can hold it.
     *
     * <p>Asserted as an exact set in both directions: a missing role breaks the
     * people who should be able to close, an extra one lets a Developer close
     * their own work, and the second is the one nobody would notice.
     */
    @Test
    void onlyAdminPmAndSupportMayCloseOrReopen() {
        assertThat(rolesFor("RESOLVED", "CLOSED"))
                .as("G-3 — a Developer may only mark Resolved")
                .containsExactlyInAnyOrder("ADMIN", "PM", "SUPPORT");

        assertThat(rolesFor("CLOSED", "REOPENED"))
                .as("blueprint §2 'Reopen ticket' — the same three, the same exclusion")
                .containsExactlyInAnyOrder("ADMIN", "PM", "SUPPORT");
    }

    /**
     * B-001 resolved role codes to ids through a JOIN. Had that JOIN matched
     * nothing the migration would still have succeeded, inserting zero grants —
     * and the first symptom would be every permission check failing in week 7.
     */
    @Test
    void everyRoleKeptItsPermissionGrants() {
        List<String> ungranted = db.sql("""
                        SELECT r.code
                          FROM roles r
                          LEFT JOIN role_permissions rp ON rp.role_id = r.id
                         GROUP BY r.id, r.code
                        HAVING COUNT(rp.permission_id) = 0
                        """)
                .query(String.class).list();

        assertThat(ungranted)
                .as("a seed JOIN that matches nothing still reports success")
                .isEmpty();
    }

    /**
     * Blueprint §2: "Edit / delete history or ribbon — ❌ (nobody can)". The
     * permission row exists so the Role Master can render it greyed out; the
     * moment it acquires a grant, the append-only guarantee has a hole in it
     * that no trigger will report.
     */
    @Test
    void nobodyCanEditHistory() {
        Integer grants = db.sql("""
                        SELECT COUNT(*)
                          FROM role_permissions rp
                          JOIN permissions p ON p.id = rp.permission_id
                         WHERE p.code = 'history.edit_delete'
                        """)
                .query(Integer.class).single();

        assertThat(grants)
                .as("the one permission that must never be granted to anyone")
                .isZero();
    }

    // ------------------------------------------------------------------
    // Cardinalities — a half-run seed looks exactly like a complete one
    // ------------------------------------------------------------------

    /**
     * Each figure is specified, not observed: six roles and the permission
     * matrix from blueprint §2, eleven task types and four priorities from
     * §4B.1, eight statuses from §3, three templates from §4A.9.
     */
    @Test
    void everySeedLoadedItsFullComplement() {
        assertThat(count("roles")).as("blueprint §2 — six roles").isEqualTo(6);
        assertThat(count("permissions")).as("§2 — one row per capability").isEqualTo(18);
        assertThat(count("task_types")).as("§4B.1 — eleven types").isEqualTo(11);
        assertThat(count("priorities")).as("§4B.1 — Low/Medium/High/Critical").isEqualTo(4);
        assertThat(count("statuses")).as("§3 — eight statuses").isEqualTo(8);
        assertThat(count("workflow_templates")).as("§4A.9 — three templates").isEqualTo(3);
        assertThat(count("workflow_stages")).as("8 + 5 + 5 stages").isEqualTo(18);
    }

    /** Exactly one default, or ticket creation has to guess which template to apply. */
    @Test
    void exactlyOneWorkflowTemplateIsTheDefault() {
        assertThat(db.sql("SELECT COUNT(*) FROM workflow_templates WHERE is_default = 1")
                .query(Integer.class).single())
                .as("Standard Dev Flow is the template every other one is a reduction of")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------

    private List<String> rolesFor(String from, String to) {
        return db.sql("""
                        SELECT role_code FROM workflow_transitions
                         WHERE from_status = :from AND to_status = :to AND is_active = 1
                        """)
                .param("from", from)
                .param("to", to)
                .query(String.class).list();
    }

    private Integer count(String table) {
        // Table names cannot be bound as parameters; every caller above passes a
        // literal, so there is no user input anywhere near this.
        return db.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }
}
