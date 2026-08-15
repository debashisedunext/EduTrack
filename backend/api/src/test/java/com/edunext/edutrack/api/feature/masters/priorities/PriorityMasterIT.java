package com.edunext.edutrack.api.feature.masters.priorities;

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
import static org.assertj.core.api.Assertions.tuple;

/**
 * B-021 · S-12 against a real MySQL.
 *
 * <p>{@link PriorityServiceTest} proves the decisions against mocks. This proves
 * the half a mock cannot: that B-002's seed is actually shaped the way the
 * screen assumes, that {@code uq_priorities_code} agrees with the service's own
 * uniqueness check, and — the part worth the Docker container —
 * <b>that the three usage counts read the columns they claim to</b>. Every one
 * of them keys on the level <em>code</em> against a {@code VARCHAR} in another
 * table, because nothing holds {@code priorities.id}. A join written against the
 * id instead would compile, run, and return zero for every level, and every
 * assertion in the mock suite would still pass.
 *
 * <p><b>The fixture restores the seed rather than creating its own rows.</b>
 * Every other master's IT prefixes throwaway rows and deletes them; this one
 * cannot, because the service refuses any code outside the contract's four —
 * which is the task's own headline refusal and would be a strange thing to work
 * around in its own test. So the four seeded levels are snapshotted to their
 * B-002 values before and after each test instead.
 */
@SpringBootTest
@Testcontainers
class PriorityMasterIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_priority_master_it")
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
    PriorityService service;

    @Autowired
    JdbcTemplate jdbc;

    /**
     * Exactly the four rows of {@code V20260807_1000}, restored field by field.
     *
     * <p><b>{@code task_types.default_level} is restored too</b>, and that is
     * not belt-and-braces. Two tests below have to clear the task types
     * defaulting to LOW in order to retire it — otherwise the guard they are not
     * testing fires first — and without this the next test to ask LOW for its
     * {@code taskTypeCount} would get whichever answer the previous one left
     * behind. An order-dependent suite passes locally and fails in CI on the day
     * somebody adds a test above it.
     */
    @BeforeEach
    @AfterEach
    void restoreTheSeed() {
        jdbc.update("DELETE FROM sla_policies WHERE resolution_hrs = 99.00");
        jdbc.update("DELETE FROM task_types WHERE code LIKE 'ITPR%'");

        setSeed("LOW", "Low", "#10B981", 72, 0, 10);
        setSeed("MEDIUM", "Medium", "#3B82F6", 24, 0, 20);
        setSeed("HIGH", "High", "#F59E0B", 8, 0, 30);
        setSeed("CRITICAL", "Critical", "#EF4444", 4, 1, 40);

        // B-002's own mapping, for the three types it seeds at LOW.
        jdbc.update("UPDATE task_types SET default_level = 'LOW' WHERE code IN "
                + "('FUTURE_RELEASE', 'BROWSER_ISSUE', 'OTHER')");
    }

    private void setSeed(String code, String name, String colour, int hours, int trigger, int seq) {
        jdbc.update("UPDATE priorities SET name = ?, colour = ?, default_sla_hours = ?, "
                        + "is_escalation_trigger = ?, seq = ?, is_active = 1 WHERE code = ?",
                name, colour, hours, trigger, seq, code);
    }

    // ------------------------------------------------------------------
    // what B-002 actually seeded
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the four seeded levels are all readable through the master")
    void seedIsShapedTheWayTheScreenAssumes() {
        // Until this task nothing served this table over HTTP at all — the
        // contract, the MSW mock and the generated client have described
        // `GET /masters/priorities` since D-001 with no controller behind it.
        // Nothing had ever read the seed the way a screen would.
        assertThat(service.list(false))
                .extracting(PriorityDtos.PriorityView::level)
                .containsExactly("LOW", "MEDIUM", "HIGH", "CRITICAL");
    }

    /**
     * §12.1 states these four hexes exactly, under "Level chips" — the one
     * colour mapping in the blueprint that is given rather than designed. The
     * MSW mock has never matched them, which B-021 also corrects.
     */
    @Test
    @DisplayName("the seeded colours are the §12.1 level chips, not approximations")
    void seededColoursAreTheBlueprintTokens() {
        assertThat(service.list(false))
                .extracting(PriorityDtos.PriorityView::level, PriorityDtos.PriorityView::colour)
                .containsExactly(
                        tuple("LOW", "#10B981"),
                        tuple("MEDIUM", "#3B82F6"),
                        tuple("HIGH", "#F59E0B"),
                        tuple("CRITICAL", "#EF4444"));
    }

    @Test
    @DisplayName("the grid comes back in seq order — severity rank, never the id")
    void gridIsInSeqOrder() {
        assertThat(service.list(true)).extracting(PriorityDtos.PriorityView::seq).isSorted();
    }

    /**
     * The invariant nothing in the codebase enforced before this task. The
     * column is a bare {@code TINYINT(1) NOT NULL DEFAULT 0} with no uniqueness
     * constraint, and §6 needs it to point at exactly one row.
     */
    @Test
    @DisplayName("exactly one seeded level is the escalation target, and it is Critical")
    void exactlyOneEscalationTarget() {
        List<PriorityDtos.PriorityView> flagged = service.list(true).stream()
                .filter(PriorityDtos.PriorityView::autoEscalates)
                .toList();

        assertThat(flagged).singleElement()
                .extracting(PriorityDtos.PriorityView::level).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("every seeded level has a default SLA, so rung 4 of the §6 ladder always answers")
    void everySeededLevelAnswersRungFour() {
        assertThat(service.list(false)).allSatisfy(level ->
                assertThat(level.defaultSlaHrs()).isNotNull().isPositive());
    }

    // ------------------------------------------------------------------
    // the counts, against the columns that actually hold the code
    // ------------------------------------------------------------------

    /**
     * The reason this suite needs a database. All three counts key on the level
     * <b>code</b> against a {@code VARCHAR} in another table, because nothing
     * holds {@code priorities.id} — the deliberate absence of referential
     * integrity A-007's migration comment describes as the trade that lets a
     * level be retired without rewriting history. A count written against the id
     * would return zero everywhere and no mock could tell.
     */
    @Test
    @DisplayName("taskTypeCount counts task_types.default_level, not a join on the id")
    void taskTypeCountReadsTheCodeColumn() {
        assertThat(find("HIGH").taskTypeCount()).as("seeded types default to HIGH").isPositive();

        jdbc.update("INSERT INTO task_types (code, name, colour, default_level, seq) "
                + "VALUES ('ITPR_ONE', 'IT Priority One', '#4F46E5', 'LOW', 900)");

        assertThat(find("LOW").taskTypeCount()).isPositive();
    }

    @Test
    @DisplayName("a retired task type stops counting — it can no longer block anything")
    void retiredTaskTypesAreNotCounted() {
        int before = find("LOW").taskTypeCount();

        jdbc.update("INSERT INTO task_types (code, name, colour, default_level, seq, is_active) "
                + "VALUES ('ITPR_OFF', 'IT Priority Off', '#4F46E5', 'LOW', 901, 0)");

        assertThat(find("LOW").taskTypeCount())
                .as("a retired type cannot fail its own validation, so it is not a blocker")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("slaPolicyCount counts sla_policies.level across every project")
    void slaPolicyCountReadsTheCodeColumn() {
        int before = find("MEDIUM").slaPolicyCount();

        jdbc.update("INSERT INTO sla_policies (project_id, task_type_id, level, resolution_hrs) "
                + "VALUES (NULL, NULL, 'MEDIUM', 99.00)");

        assertThat(find("MEDIUM").slaPolicyCount()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("ticketCount reads tickets.level and runs against the real column")
    void ticketCountRunsAgainstTheRealColumn() {
        // The statement is the assertion. `tickets.level` is a VARCHAR holding
        // the code; a count written against `priorities.id` would fail here with
        // an unknown column rather than quietly answering zero.
        assertThat(service.list(true))
                .allSatisfy(level -> assertThat(level.ticketCount()).isNotNegative());
    }

    // ------------------------------------------------------------------
    // uniqueness, at the index and at the service
    // ------------------------------------------------------------------

    @Test
    @DisplayName("uq_priorities_code refuses a duplicate the service would have caught first")
    void theIndexAgreesWithTheService() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO priorities (code, name, colour, seq) VALUES ('HIGH', 'Dup', '#F59E0B', 99)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("creating a level that already exists is refused with a message, not an index name")
    void duplicateCreateIsRefusedInTheService() {
        assertThatThrownBy(() -> service.create(new PriorityDtos.PriorityWrite(
                "HIGH", "Elevated", "#F59E0B", null, null, null, null)))
                .isInstanceOf(PriorityService.DuplicatePriorityException.class)
                .hasMessageContaining("already exists");
    }

    /**
     * S-12's unfulfilled promise, asserted against a real database so that the
     * day the contract's {@code Level} enum opens, this test is what fails and
     * points at the decision rather than the day being discovered by a broken
     * screen.
     */
    @Test
    @DisplayName("a fifth level cannot be created, and the refusal names what has to change")
    void aFifthLevelIsStillRefused() {
        assertThatThrownBy(() -> service.create(new PriorityDtos.PriorityWrite(
                "URGENT", "Urgent", "#EF4444", null, null, null, null)))
                .isInstanceOf(PriorityService.PriorityValidationException.class)
                .hasMessageContaining("closed four-value enum");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM priorities WHERE code = 'URGENT'", Integer.class)).isZero();
    }

    // ------------------------------------------------------------------
    // the two cross-row rules, end to end
    // ------------------------------------------------------------------

    @Test
    @DisplayName("moving the escalation flag clears the incumbent in the same transaction")
    void movingTheFlagIsAtomic() {
        int highId = idOf("HIGH");

        service.update(highId, patch(null, null, true, null));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM priorities WHERE is_escalation_trigger = 1", Integer.class))
                .as("exactly one, always").isEqualTo(1);
        assertThat(find("HIGH").autoEscalates()).isTrue();
        assertThat(find("CRITICAL").autoEscalates()).isFalse();
    }

    @Test
    @DisplayName("clearing the last escalation flag is refused and nothing is written")
    void clearingTheLastFlagIsRefused() {
        int criticalId = idOf("CRITICAL");

        assertThatThrownBy(() -> service.update(criticalId, patch(null, null, false, null)))
                .isInstanceOf(PriorityService.EscalationTargetException.class);

        assertThat(find("CRITICAL").autoEscalates()).isTrue();
    }

    @Test
    @DisplayName("retiring a level active task types default to is refused, and names them")
    void retiringABlockedLevelIsRefused() {
        jdbc.update("INSERT INTO task_types (code, name, colour, default_level, seq) "
                + "VALUES ('ITPR_BLK', 'IT Priority Blocker', '#4F46E5', 'LOW', 902)");
        int lowId = idOf("LOW");

        assertThatThrownBy(() -> service.update(lowId, patch(null, null, null, false)))
                .isInstanceOf(PriorityService.PriorityInUseException.class)
                .hasMessageContaining("IT Priority Blocker");

        assertThat(find("LOW").isActive()).isTrue();
    }

    @Test
    @DisplayName("retiring a level nothing defaults to succeeds, whatever the SLA rows say")
    void retiringAnUnblockedLevelSucceeds() {
        jdbc.update("UPDATE task_types SET default_level = 'MEDIUM' WHERE default_level = 'LOW'");
        jdbc.update("INSERT INTO sla_policies (project_id, task_type_id, level, resolution_hrs) "
                + "VALUES (NULL, NULL, 'LOW', 99.00)");
        int lowId = idOf("LOW");

        service.update(lowId, patch(null, null, null, false));

        assertThat(find("LOW").isActive()).isFalse();
        assertThat(service.list(false)).extracting(PriorityDtos.PriorityView::level)
                .as("gone from the picker and from every SLA matrix's columns")
                .doesNotContain("LOW");
        assertThat(service.list(true)).extracting(PriorityDtos.PriorityView::level)
                .as("still on the grid, still nameable by every ticket that carries it")
                .contains("LOW");
    }

    @Test
    @DisplayName("a retired level's SLA policy rows survive it and come back on reactivation")
    void retiringDeletesNothing() {
        jdbc.update("UPDATE task_types SET default_level = 'MEDIUM' WHERE default_level = 'LOW'");
        jdbc.update("INSERT INTO sla_policies (project_id, task_type_id, level, resolution_hrs) "
                + "VALUES (NULL, NULL, 'LOW', 99.00)");
        int lowId = idOf("LOW");

        service.update(lowId, patch(null, null, null, false));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sla_policies WHERE level = 'LOW' AND resolution_hrs = 99.00",
                Integer.class)).isOne();

        service.update(lowId, patch(null, null, null, true));
        assertThat(find("LOW").isActive()).isTrue();
        assertThat(find("LOW").slaPolicyCount()).isPositive();
    }

    @Test
    @DisplayName("the level code cannot be changed, because nothing would cascade the rename")
    void theCodeIsImmutable() {
        int highId = idOf("HIGH");

        assertThatThrownBy(() -> service.update(highId,
                patch("ELEVATED", null, null, null)))
                .isInstanceOf(PriorityService.ImmutablePriorityCodeException.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM priorities WHERE code = 'HIGH'", Integer.class)).isOne();
    }

    @Test
    @DisplayName("resending the stored code is a no-op, because S-12 submits the whole form")
    void resendingTheCodeIsANoOp() {
        int highId = idOf("HIGH");

        service.update(highId, patch("HIGH", "Elevated", null, null));

        assertThat(find("HIGH").name()).isEqualTo("Elevated");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private PriorityDtos.PriorityView find(String code) {
        return service.list(true).stream()
                .filter(p -> p.level().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no level " + code));
    }

    private int idOf(String code) {
        return find(code).id();
    }

    /**
     * A patch touching only the fields these tests vary. {@code defaultSlaHrs}
     * goes as {@code null} — <b>absent</b>, not {@code Optional.empty()}, which
     * would clear it. {@link PriorityPatchTest} is where that distinction is
     * pinned; here it just has to not be got wrong.
     */
    private static PriorityDtos.PriorityPatch patch(String level, String name,
                                                    Boolean autoEscalates, Boolean isActive) {
        return new PriorityDtos.PriorityPatch(level, name, null, null, autoEscalates, null, isActive);
    }
}
