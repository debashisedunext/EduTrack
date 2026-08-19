package com.edunext.edutrack.api.feature.masters.modules;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-064 · §7.3's module master against a real MySQL.
 *
 * <p>{@code ModuleServiceTest} proves the decisions against mocks. This proves
 * the half a mock cannot: that C-065's seed is actually shaped the way three of
 * Stream C's shipped screens assume, that {@code uq_product_modules_code} is
 * there, and that the foreign key from {@code tickets.module_id} really does
 * make "retire, never delete" necessary rather than merely tidy.
 *
 * <p>Fixture rows are prefixed {@code ITMOD} so nothing collides with the seed
 * and the cleanup can be exact.
 */
@SpringBootTest
@Testcontainers
class ModuleMasterIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_module_master_it")
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
    ModuleService service;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clearFixtureRows() {
        jdbc.update("DELETE FROM product_modules WHERE code LIKE 'ITMOD%'");
    }

    // ------------------------------------------------------------------
    // what C-065 actually seeded
    // ------------------------------------------------------------------

    /**
     * The eight of §7.3, in the order it lists them.
     *
     * <p>Order matters beyond tidiness: this list <em>is</em> the create form's
     * module picker, so a seed inserted alphabetically would silently reorder a
     * screen the blueprint specifies.
     */
    @Test
    @DisplayName("the eight seeded modules are readable, in blueprint order")
    void seedIsShapedTheWayTheScreensAssume() {
        assertThat(seededOnly())
                .extracting(ModuleDtos.ModuleView::name)
                .containsExactly("Student", "Admission", "Fees", "Examination",
                        "Attendance", "Library", "Inventory", "Parent App");
    }

    /** Codes are the stable identifier — screens key behaviour off these, not names. */
    @Test
    @DisplayName("the seeded codes are the blueprint vocabulary")
    void seededCodesAreStable() {
        assertThat(seededOnly())
                .extracting(ModuleDtos.ModuleView::code)
                .containsExactly("STUDENT", "ADMISSION", "FEES", "EXAMINATION",
                        "ATTENDANCE", "LIBRARY", "INVENTORY", "PARENT_APP");
    }

    /** Everything seeded is live; nothing arrives retired. */
    @Test
    @DisplayName("every seeded module is active")
    void seedIsAllActive() {
        assertThat(seededOnly()).allMatch(ModuleDtos.ModuleView::isActive);
    }

    // ------------------------------------------------------------------
    // the rule the route exists for
    // ------------------------------------------------------------------

    /**
     * <b>The one behaviour the seed alone cannot demonstrate.</b>
     *
     * <p>All eight seeded rows are active, so every assertion above would pass
     * just as well against an implementation that filtered retired rows out.
     * D-060's mock carries a deactivated {@code Transport} for this reason; a
     * real database needs one inserted.
     */
    @Test
    @DisplayName("a deactivated module is still returned, carrying isActive false")
    void retiredRowsSurviveTheRead() {
        insertFixture("ITMOD_TRANSPORT", "IT Transport", 900, false);

        assertThat(service.list())
                .filteredOn(module -> module.code().equals("ITMOD_TRANSPORT"))
                .singleElement()
                .satisfies(module -> {
                    assertThat(module.isActive()).isFalse();
                    assertThat(module.name()).isEqualTo("IT Transport");
                });
    }

    /**
     * {@code seq} ascending, {@code id} breaking the tie.
     *
     * <p>The tiebreak is not hypothetical here: {@code seq} carries no unique
     * index, so two rows sharing a value are legal, and without the second sort
     * key MySQL is free to return them in either order between reads — a picker
     * whose two entries swap places on refresh.
     */
    @Test
    @DisplayName("rows sharing a seq come back in a stable id order")
    void seqTiesBreakOnId() {
        insertFixture("ITMOD_A", "IT A", 900, true);
        insertFixture("ITMOD_B", "IT B", 900, true);

        assertThat(service.list())
                .filteredOn(module -> module.code().startsWith("ITMOD_"))
                .extracting(ModuleDtos.ModuleView::code)
                .containsExactly("ITMOD_A", "ITMOD_B");
    }

    /** A ninth module is a row somebody inserts — no migration, no release. */
    @Test
    @DisplayName("a new row is served without a code change")
    void aNinthModuleIsJustARow() {
        insertFixture("ITMOD_HOSTEL", "IT Hostel", 900, true);

        assertThat(service.list())
                .extracting(ModuleDtos.ModuleView::code)
                .contains("ITMOD_HOSTEL")
                .hasSize(9);
    }

    // ------------------------------------------------------------------
    // what the schema enforces underneath
    // ------------------------------------------------------------------

    @Test
    @DisplayName("uq_product_modules_code refuses a duplicate code")
    void codesAreUnique() {
        insertFixture("ITMOD_DUP", "IT Dup", 900, true);

        assertThatThrownBy(() -> insertFixture("ITMOD_DUP", "IT Dup Again", 901, true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * <b>Why "retire, never delete" is a constraint and not a preference.</b>
     *
     * <p>C-065 left {@code fk_tickets_module} at its default {@code RESTRICT},
     * so a module any ticket points at cannot be removed — which is what keeps
     * the read above able to name it. The service has no delete to test, so
     * what is asserted is the guarantee it depends on, read out of
     * {@code information_schema} rather than assumed from the migration file:
     * a later {@code ON DELETE SET NULL} would break nothing in Java and
     * quietly turn every historical module reference into a blank cell.
     *
     * <p>Asserted here rather than with a ticket fixture because no migration
     * seeds {@code tickets} — B-007's corpus is a dev-profile loader, not
     * schema, so this container has no ticket row to point at and inserting one
     * would mean satisfying the whole of C's write path to test B's read.
     */
    @Test
    @DisplayName("the ticket foreign key restricts deletes, so a used module cannot vanish")
    void theTicketForeignKeyRestrictsDeletes() {
        List<String> deleteRule = jdbc.queryForList("""
                        SELECT rc.delete_rule
                          FROM information_schema.referential_constraints rc
                         WHERE rc.constraint_schema = DATABASE()
                           AND rc.constraint_name = 'fk_tickets_module'
                        """, String.class);

        // RESTRICT and NO ACTION are the same rule in InnoDB, and which of the
        // two `information_schema` reports back depends on whether the clause
        // was written out — C-065 relied on the default, so both spellings are
        // the intended state. What must never appear here is CASCADE or
        // SET NULL.
        assertThat(deleteRule)
                .as("fk_tickets_module is what stops a module a ticket names being deleted")
                .singleElement()
                .isIn("RESTRICT", "NO ACTION");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** The seed, with any fixture row this suite inserted filtered back out. */
    private List<ModuleDtos.ModuleView> seededOnly() {
        return service.list().stream().filter(module -> !module.code().startsWith("ITMOD")).toList();
    }

    private void insertFixture(String code, String name, int seq, boolean active) {
        jdbc.update("INSERT INTO product_modules (code, name, seq, is_active) VALUES (?, ?, ?, ?)",
                code, name, seq, active);
    }
}
