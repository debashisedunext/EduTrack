package com.edunext.edutrack.api.feature.masters.tasktypes;

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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-020 · S-11 against a real MySQL.
 *
 * <p>{@code TaskTypeServiceTest} proves the decisions against mocks. This proves
 * the half a mock cannot: that B-002's seed is actually shaped the way the
 * screen assumes, that {@code uq_task_types_code} agrees with the service's own
 * uniqueness check, and that the three foreign keys pointing at this table
 * really are what makes "deactivate, never delete" necessary rather than merely
 * tidy.
 *
 * <p>Fixture rows are prefixed {@code ITTT} so nothing collides with the seed or
 * with the SLA suites, and the cleanup can be exact.
 */
@SpringBootTest
@Testcontainers
class TaskTypeMasterIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_task_type_master_it")
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
    TaskTypeService service;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clearFixtureRows() {
        // The policy first: it is a referent, and the whole point of this suite
        // is that a referenced task type cannot be removed.
        jdbc.update("DELETE FROM sla_policies WHERE level = 'ITTTLVL'");
        jdbc.update("DELETE FROM task_types WHERE code LIKE 'ITTT%'");
    }

    // ------------------------------------------------------------------
    // what B-002 actually seeded
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the eleven seeded types are all readable through the master")
    void seedIsShapedTheWayTheScreenAssumes() {
        List<TaskTypeDtos.TaskTypeView> seeded = seededOnly();

        assertThat(seeded).hasSize(11);
        assertThat(seeded).extracting(TaskTypeDtos.TaskTypeView::name)
                .contains("Change Request", "Production Bug", "Client Request", "Future Release",
                        "Internal Bug", "Client Bug", "Server Issue", "Network Issue",
                        "Browser Issue", "Performance Issue", "Other");
    }

    @Test
    @DisplayName("every seeded type has a colour the design system can render")
    void seededColoursAreTokens() {
        // The read schema declares `pattern: ^#[0-9A-Fa-f]{6}$`. A seeded row
        // that did not match would serialise into a response the generated
        // client's own zod schema rejects — and nothing else would notice,
        // because until B-020 nothing read this table over HTTP.
        assertThat(seededOnly()).allSatisfy(type ->
                assertThat(type.colour()).matches("^#[0-9A-Fa-f]{6}$"));
    }

    @Test
    @DisplayName("every seeded default level is one the priority master still holds")
    void seededDefaultLevelsResolve() {
        // The create path enforces this. The seed predates the create path, so
        // nothing had ever checked that B-002 and B-002's own priorities agree.
        assertThat(seededOnly()).allSatisfy(type ->
                assertThat(TaskTypeService.CONTRACT_LEVELS).contains(type.defaultLevel()));
    }

    @Test
    @DisplayName("the grid comes back in seq order, which is the order §S-11 lists them in")
    void gridIsInSeqOrder() {
        assertThat(seededOnly()).extracting(TaskTypeDtos.TaskTypeView::seq).isSorted();
        assertThat(seededOnly()).first()
                .extracting(TaskTypeDtos.TaskTypeView::name)
                .isEqualTo("Change Request");
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a created type is readable, and sorts after the eleven")
    void createLandsAtTheEnd() {
        TaskTypeDtos.TaskTypeView created = service.create(write("ITTT_DATA_FIX", "ITTT Data Fix"));

        assertThat(created.id()).isPositive();
        assertThat(created.seq()).isGreaterThan((short) 110);
        assertThat(created.ticketCount()).isZero();
        assertThat(service.find(created.id())).isPresent();
    }

    @Test
    @DisplayName("the service refuses a duplicate code before the index has to")
    void duplicateCodeIsRefusedByTheService() {
        // The index is what is actually true under a race; the service check is
        // the field-keyed message the form can land on an input. B-013 makes the
        // same argument about the resource form's three uniqueness rules.
        service.create(write("ITTT_DUP", "ITTT Dup"));

        assertThatThrownBy(() -> service.create(write("ittt_dup", "ITTT Dup Two")))
                .isInstanceOf(TaskTypeService.DuplicateTaskTypeException.class);
    }

    @Test
    @DisplayName("and uq_task_types_code is really there, so the race is covered too")
    void theIndexBacksTheCheck() {
        service.create(write("ITTT_IDX", "ITTT Idx"));

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO task_types (code, name, colour, default_level, seq) "
                        + "VALUES ('ITTT_IDX', 'ITTT Idx Again', '#4F46E5', 'LOW', 900)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the name check agrees with utf8mb4_0900_ai_ci, which is case-insensitive")
    void duplicateNameIsRefusedWhateverTheCase() {
        // findByNameIgnoreCase is UPPER()-based and the collation is ai_ci, so
        // the two agree today. If somebody changed the collation, this is what
        // would say so — B-013's ITFRM.CASED test, one table over.
        service.create(write("ITTT_NAME_A", "ITTT Shared Name"));

        assertThatThrownBy(() -> service.create(write("ITTT_NAME_B", "ittt shared name")))
                .isInstanceOf(TaskTypeService.DuplicateTaskTypeException.class)
                .extracting(e -> ((TaskTypeService.DuplicateTaskTypeException) e).field())
                .isEqualTo("name");
    }

    // ------------------------------------------------------------------
    // update
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a full-form save that resends the stored code is not a conflict")
    void resendingTheCodeIsANoOp() {
        // S-11 submits the whole form on every save. Any other reading makes
        // every edit to any task type a 409.
        TaskTypeDtos.TaskTypeView created = service.create(write("ITTT_RESEND", "ITTT Resend"));

        TaskTypeDtos.TaskTypePatch patch = new TaskTypeDtos.TaskTypePatch();
        patch.setCode("ITTT_RESEND");
        patch.setName("ITTT Resend Renamed");

        assertThat(service.update(created.id(), patch)).get()
                .extracting(TaskTypeDtos.TaskTypeView::name)
                .isEqualTo("ITTT Resend Renamed");
    }

    @Test
    @DisplayName("clearing the default SLA really writes NULL, not zero")
    void clearingTheSlaWritesNull() {
        // Zero and absent are different facts here for the reason B-018 gives:
        // SlaResolution treats a non-positive figure as no target, so a zero
        // written where NULL was meant would read as configured and behave as
        // absent.
        TaskTypeDtos.TaskTypeView created = service.create(write("ITTT_SLA", "ITTT Sla"));

        TaskTypeDtos.TaskTypePatch patch = new TaskTypeDtos.TaskTypePatch();
        patch.setDefaultSlaHrs(Optional.empty());
        service.update(created.id(), patch);

        Integer nulls = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_types WHERE code = 'ITTT_SLA' AND default_sla_hours IS NULL",
                Integer.class);
        assertThat(nulls).isEqualTo(1);
    }

    @Test
    @DisplayName("a retired type still comes back from the list — that is the whole point")
    void retiredTypesSurviveTheRead() {
        TaskTypeDtos.TaskTypeView created = service.create(write("ITTT_RETIRE", "ITTT Retire"));

        TaskTypeDtos.TaskTypePatch patch = new TaskTypeDtos.TaskTypePatch();
        patch.setIsActive(false);
        service.update(created.id(), patch);

        assertThat(service.list())
                .filteredOn(t -> "ITTT_RETIRE".equals(t.code()))
                .singleElement()
                .extracting(TaskTypeDtos.TaskTypeView::isActive)
                .isEqualTo(false);
    }

    // ------------------------------------------------------------------
    // why there is no delete
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the three foreign keys into task_types have no cascade — a delete cannot be safe")
    void foreignKeysAreRestrictive() {
        // This is the claim B-019's migration made in a comment and nothing
        // asserted. `tickets`, `sla_policies` and `project_task_types` all point
        // here without ON DELETE, so a delete on a type in use fails at the
        // database — and "fixing" that with a cascade would rewrite what a
        // historical ticket says it was raised against.
        List<String> cascading = jdbc.queryForList("""
                SELECT CONCAT(rc.TABLE_NAME, '.', rc.CONSTRAINT_NAME)
                  FROM information_schema.REFERENTIAL_CONSTRAINTS rc
                 WHERE rc.CONSTRAINT_SCHEMA = DATABASE()
                   AND rc.REFERENCED_TABLE_NAME = 'task_types'
                   AND rc.DELETE_RULE <> 'RESTRICT'
                   AND rc.DELETE_RULE <> 'NO ACTION'
                """, String.class);

        assertThat(cascading)
                .as("a cascade into task_types would make a delete look safe and silently "
                        + "rewrite history — B-020 exists partly to keep this list empty")
                .isEmpty();
    }

    @Test
    @DisplayName("a type something points at cannot be deleted at the database either")
    void aTypeInUseCannotBeDeleted() {
        // The floor under the "no DELETE route" decision, demonstrated rather
        // than reasoned about. `sla_policies` is the cheapest of the three
        // referents to construct — it needs no project and no ticket — and its
        // foreign key is the same shape as the other two.
        TaskTypeDtos.TaskTypeView created = service.create(write("ITTT_IN_USE", "ITTT In Use"));
        jdbc.update("INSERT INTO sla_policies (project_id, task_type_id, level, resolution_hrs) "
                + "VALUES (NULL, ?, 'ITTTLVL', 4.00)", created.id());

        try {
            assertThatThrownBy(() -> jdbc.update("DELETE FROM task_types WHERE id = ?", created.id()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.update("DELETE FROM sla_policies WHERE level = 'ITTTLVL'");
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private List<TaskTypeDtos.TaskTypeView> seededOnly() {
        return service.list().stream().filter(t -> !t.code().startsWith("ITTT")).toList();
    }

    private static TaskTypeDtos.TaskTypeWrite write(String code, String name) {
        return new TaskTypeDtos.TaskTypeWrite(
                code, name, "database", "#10B981", "MEDIUM", new BigDecimal("24.00"), null, null);
    }
}
