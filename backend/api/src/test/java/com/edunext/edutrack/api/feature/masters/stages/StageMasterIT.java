package com.edunext.edutrack.api.feature.masters.stages;

import com.edunext.edutrack.domain.workflow.WorkflowTemplateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * B-040 · S-13 tab 2 against a real MySQL.
 *
 * <p>{@link StageServiceTest} proves the decisions against mocks. This proves the
 * four things a mock cannot:
 *
 * <ol>
 *   <li><b>The reorder survives {@code uq_workflow_stages_seq}.</b> This is the
 *     assertion that justifies the whole test class. The unique key is enforced
 *     per row, so the obvious one-pass implementation collides the instant two
 *     stages swap — and against a mocked repository it passes, because a mock has
 *     no unique key. Every other rule in this package can be checked without a
 *     database; this one is invisible until there is one.</li>
 *   <li><b>The two usage counts read the columns they claim to.</b> Both key on
 *     the stage <em>code</em> against {@code VARCHAR}s in other tables, because
 *     nothing holds {@code workflow_stages.id}. A join written against the id
 *     would compile, run, and return zero for every stage — and every assertion
 *     in {@link StageServiceTest} would still pass.</li>
 *   <li><b>The template scope on those counts is real.</b> {@code DEV} exists on
 *     two of B-004's three templates as two separate rows, so a count by code
 *     alone would report one template's traffic against the other's stage.</li>
 *   <li><b>B-004's seed is shaped the way this screen assumes</b> — 8 + 5 + 5,
 *     seq in tens, and every {@code can_return_to} pointing backwards, which is
 *     the rule the screen enforces on every write and has never enforced on the
 *     seed.</li>
 *   <li><b>B-042 · {@code ck_workflow_stages_deprecation} is enforced.</b> MySQL
 *     only began enforcing {@code CHECK} at 8.0.16 and parsed-and-ignored it
 *     before that, so "the constraint is in the migration" and "the constraint
 *     refuses anything" are two different claims. Nothing in the service can
 *     produce a row that violates it — which is exactly why it needs asserting
 *     against the database rather than through the service.</li>
 * </ol>
 *
 * <p>The fixture restores what it changes rather than working on rows of its own,
 * for {@code StatusMasterIT}'s reason: the stages under test are the seeded ones
 * every other assertion here is about.
 */
@SpringBootTest
@Testcontainers
class StageMasterIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_stage_master_it")
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
    StageService service;

    @Autowired
    WorkflowTemplateRepository templates;

    @Autowired
    JdbcTemplate jdbc;

    private long standardDevFlow() {
        return templates.findByName("Standard Dev Flow").orElseThrow().getId();
    }

    private long infraFlow() {
        return templates.findByName("Infra Flow").orElseThrow().getId();
    }

    /** Restore B-004's seeded order and content after anything that moved it. */
    @AfterEach
    void restoreSeed() {
        jdbc.update("DELETE FROM workflow_stages WHERE stage_code NOT IN "
                + "('INTAKE','TRIAGE','DEV','QA','DEPLOY','VERIFY','SIGNOFF','CLOSED')");
        Map<String, Integer> seq = Map.of(
                "INTAKE", 10, "TRIAGE", 20, "DEV", 30, "QA", 40,
                "DEPLOY", 50, "VERIFY", 60, "SIGNOFF", 70, "CLOSED", 80);
        // Park first, for the same reason the service does: restoring one row at a
        // time walks straight into the unique key this class exists to prove.
        jdbc.update("UPDATE workflow_stages SET seq = seq + 1000");
        long dev = standardDevFlow();
        seq.forEach((code, s) -> jdbc.update(
                "UPDATE workflow_stages SET seq = ? WHERE template_id = ? AND stage_code = ?",
                s, dev, code));
        jdbc.update("""
                UPDATE workflow_stages SET seq = CASE stage_code
                    WHEN 'INTAKE' THEN 10 WHEN 'TRIAGE' THEN 20 WHEN 'DEV' THEN 30
                    WHEN 'SIGNOFF' THEN 40 WHEN 'CLOSED' THEN 50 END
                 WHERE template_id = (SELECT id FROM workflow_templates WHERE name = 'Support Fast-Track')
                """);
        jdbc.update("""
                UPDATE workflow_stages SET seq = CASE stage_code
                    WHEN 'INTAKE' THEN 10 WHEN 'TRIAGE' THEN 20 WHEN 'DEPLOY' THEN 30
                    WHEN 'VERIFY' THEN 40 WHEN 'CLOSED' THEN 50 END
                 WHERE template_id = (SELECT id FROM workflow_templates WHERE name = 'Infra Flow')
                """);
        jdbc.update("UPDATE workflow_stages SET display_name = 'Development', "
                + "owner_role = 'DEVELOPER' WHERE stage_code = 'DEV'");

        // B-042. Both columns, together — ck_workflow_stages_deprecation refuses
        // the row otherwise, and a retire that leaked into the next test would
        // change the answer to isDeletable on rows that test never touched.
        jdbc.update("UPDATE workflow_stages SET is_deprecated = 0, deprecated_at = NULL");

        // B-042, and this was a gap rather than an addition. `aFullReversal
        // SurvivesTheUniqueKey` nulls every can_return_to on Infra Flow to get an
        // order the direction rule would otherwise refuse, and nothing here put
        // them back — so whichever test ran next saw a template with no loop-backs
        // at all. B-040 never noticed because no assertion of its own depended on
        // them; B-042's delete does, and read the missing arrow as permission to
        // remove DEPLOY. That destroyed a seeded stage `restoreSeed` cannot
        // recreate, and the next test to count Infra Flow's stages failed instead
        // of the one that caused it.
        //
        // Restored from V20260807_1700 verbatim, per template, because the same
        // code carries different targets on different ribbons: DEPLOY returns to
        // DEV on Standard Dev Flow and to TRIAGE on Infra Flow, which has no
        // Development stage at all.
        jdbc.update("UPDATE workflow_stages SET can_return_to = NULL");
        restoreReturnTargets("Standard Dev Flow", Map.of(
                "DEV", "[\"TRIAGE\"]", "QA", "[\"DEV\"]", "DEPLOY", "[\"DEV\"]",
                "VERIFY", "[\"DEV\"]", "SIGNOFF", "[\"DEV\"]"));
        restoreReturnTargets("Support Fast-Track", Map.of(
                "DEV", "[\"TRIAGE\"]", "SIGNOFF", "[\"DEV\"]"));
        restoreReturnTargets("Infra Flow", Map.of(
                "DEPLOY", "[\"TRIAGE\"]", "VERIFY", "[\"DEPLOY\"]"));
    }

    private void restoreReturnTargets(String templateName, Map<String, String> targets) {
        targets.forEach((code, json) -> jdbc.update(
                "UPDATE workflow_stages SET can_return_to = ? WHERE stage_code = ? "
                        + "AND template_id = (SELECT id FROM workflow_templates WHERE name = ?)",
                json, code, templateName));
    }

    // ------------------------------------------------------------------
    // the seed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("B-004 seeded three templates — 8, 5 and 5 stages")
    void theSeedIsShapedAsTheScreenAssumes() {
        assertThat(service.templates())
                .extracting(StageDtos.WorkflowTemplateView::name, StageDtos.WorkflowTemplateView::stageCount,
                        StageDtos.WorkflowTemplateView::isDefault)
                .containsExactlyInAnyOrder(
                        tuple("Standard Dev Flow", 8, true),
                        tuple("Support Fast-Track", 5, false),
                        tuple("Infra Flow", 5, false));
    }

    @Test
    @DisplayName("Standard Dev Flow reads left to right in §4A.1's order")
    void theRibbonReadsInOrder() {
        assertThat(service.list(standardDevFlow()).orElseThrow())
                .extracting(StageDtos.StageView::stageCode, StageDtos.StageView::position)
                .containsExactly(
                        tuple("INTAKE", 1), tuple("TRIAGE", 2), tuple("DEV", 3), tuple("QA", 4),
                        tuple("DEPLOY", 5), tuple("VERIFY", 6), tuple("SIGNOFF", 7),
                        tuple("CLOSED", 8));
    }

    @Test
    @DisplayName("DEV's sla_hours is null on purpose — §4A.1 resolves it from the SLA matrix")
    void devHasNoTemplateLevelSla() {
        StageDtos.StageView dev = service.list(standardDevFlow()).orElseThrow().stream()
                .filter(s -> s.stageCode().equals("DEV")).findFirst().orElseThrow();

        assertThat(dev.slaHours()).isNull();
    }

    /**
     * The rule every write in this package enforces, checked against the rows
     * nothing enforced it on.
     *
     * <p>B-004 wrote {@code can_return_to} by hand from §4A.1's loop-back table
     * before any code validated it. If the seed disagreed with the rule, the very
     * first reorder an Admin attempted would be refused for a state they did not
     * create — the worst kind of refusal, because there is nothing they can do
     * about it from the screen.
     */
    @Test
    @DisplayName("every seeded can_return_to points backwards, on all three templates")
    void theSeedObeysTheBackwardRule() {
        for (StageDtos.WorkflowTemplateView template : service.templates()) {
            List<StageDtos.StageView> ribbon = service.list(template.id()).orElseThrow();
            Map<String, Integer> position = ribbon.stream().collect(
                    java.util.stream.Collectors.toMap(
                            StageDtos.StageView::stageCode, StageDtos.StageView::position));

            for (StageDtos.StageView stage : ribbon) {
                for (String target : stage.canReturnTo()) {
                    assertThat(position.get(target))
                            .as("%s · %s → %s must point backwards",
                                    template.name(), stage.stageCode(), target)
                            .isNotNull()
                            .isLessThan(stage.position());
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // the reorder, which is why this class exists
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a swap survives uq_workflow_stages_seq — one pass would deadlock on it")
    void aSwapSurvivesTheUniqueKey() {
        long template = standardDevFlow();
        List<Long> ids = service.list(template).orElseThrow().stream()
                .map(StageDtos.StageView::id).toList();

        // Swap the first two. In one pass, writing 10 onto the row at 20 collides
        // with the row still sitting at 10 and the whole transaction rolls back.
        List<Long> swapped = new java.util.ArrayList<>(ids);
        java.util.Collections.swap(swapped, 0, 1);

        assertThatCode(() -> service.reorder(template, swapped)).doesNotThrowAnyException();

        assertThat(service.list(template).orElseThrow())
                .extracting(StageDtos.StageView::stageCode, StageDtos.StageView::seq)
                .startsWith(tuple("TRIAGE", (short) 10), tuple("INTAKE", (short) 20));
    }

    @Test
    @DisplayName("a full reversal survives it too — every row collides with another")
    void aFullReversalSurvivesTheUniqueKey() {
        long template = infraFlow();
        // Infra Flow's loop-backs would be inverted by a reversal, so clear them
        // first — the direction rule is asserted separately and is not what this
        // test is about.
        jdbc.update("UPDATE workflow_stages SET can_return_to = NULL WHERE template_id = ?",
                template);

        List<Long> reversed = service.list(template).orElseThrow().stream()
                .map(StageDtos.StageView::id)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toList(), l -> {
                            java.util.Collections.reverse(l);
                            return l;
                        }));

        assertThatCode(() -> service.reorder(template, reversed)).doesNotThrowAnyException();

        assertThat(service.list(template).orElseThrow())
                .extracting(StageDtos.StageView::stageCode)
                .containsExactly("CLOSED", "VERIFY", "DEPLOY", "TRIAGE", "INTAKE");
    }

    @Test
    @DisplayName("the parked values never survive the transaction — seq comes back as 10, 20, 30")
    void parkingValuesAreNotLeftBehind() {
        long template = standardDevFlow();
        List<Long> ids = service.list(template).orElseThrow().stream()
                .map(StageDtos.StageView::id).toList();

        service.reorder(template, ids);

        assertThat(service.list(template).orElseThrow())
                .extracting(StageDtos.StageView::seq)
                .containsExactly((short) 10, (short) 20, (short) 30, (short) 40,
                        (short) 50, (short) 60, (short) 70, (short) 80);
    }

    @Test
    @DisplayName("a refused reorder leaves the stored order untouched")
    void arefusedReorderChangesNothing() {
        long template = standardDevFlow();
        List<StageDtos.StageView> before = service.list(template).orElseThrow();

        // DEV → TRIAGE inverted by putting DEV first.
        List<Long> ids = new java.util.ArrayList<>(before.stream()
                .map(StageDtos.StageView::id).toList());
        java.util.Collections.swap(ids, 1, 2);

        assertThatThrownBy(() -> service.reorder(template, ids))
                .isInstanceOf(StageService.ReturnTargetDirectionException.class);

        assertThat(service.list(template).orElseThrow())
                .extracting(StageDtos.StageView::stageCode)
                .isEqualTo(before.stream().map(StageDtos.StageView::stageCode).toList());
    }

    // ------------------------------------------------------------------
    // the usage counts
    // ------------------------------------------------------------------

    @Test
    @DisplayName("with no tickets, every stage is code-editable — the counts really read zero")
    void countsAreZeroOnAnEmptyDatabase() {
        assertThat(service.list(standardDevFlow()).orElseThrow())
                .allMatch(StageDtos.StageView::isCodeEditable)
                .allMatch(s -> s.transitionCount() == 0 && s.openTicketCount() == 0);
    }

    /**
     * The count joins on {@code stage_code} against a {@code VARCHAR}, and it has
     * to: nothing anywhere holds {@code workflow_stages.id}.
     *
     * <p>Written as raw inserts rather than through the ticket API because what is
     * being proved is which column the SQL reads, and a ticket created through
     * Stream C's service would prove that plus a great deal that is not this
     * package's.
     */
    @Test
    @DisplayName("a ticket standing in a stage freezes that stage's code, and only that one")
    void anOpenTicketFreezesItsOwnStage() {
        long template = standardDevFlow();
        insertTicket(template, "QA");

        List<StageDtos.StageView> ribbon = service.list(template).orElseThrow();

        assertThat(ribbon).filteredOn(s -> s.stageCode().equals("QA"))
                .singleElement()
                .matches(s -> s.openTicketCount() == 1 && !s.isCodeEditable());
        assertThat(ribbon).filteredOn(s -> !s.stageCode().equals("QA"))
                .allMatch(StageDtos.StageView::isCodeEditable);

        long qa = ribbon.stream().filter(s -> s.stageCode().equals("QA"))
                .findFirst().orElseThrow().id();
        assertThatThrownBy(() -> service.update(template, qa,
                new StageDtos.StagePatch("TESTING", null, null, null, null, null, null)))
                .isInstanceOf(StageService.ImmutableStageCodeException.class);

        removeTicket();
    }

    /**
     * The template scope, which is the half most likely to be dropped as
     * redundant.
     */
    @Test
    @DisplayName("a ticket on one template does not freeze the same code on another")
    void countsAreScopedToTheTemplate() {
        long fastTrack = templates.findByName("Support Fast-Track").orElseThrow().getId();
        insertTicket(fastTrack, "DEV");

        StageDtos.StageView devOnStandard = service.list(standardDevFlow()).orElseThrow()
                .stream().filter(s -> s.stageCode().equals("DEV")).findFirst().orElseThrow();
        StageDtos.StageView devOnFastTrack = service.list(fastTrack).orElseThrow()
                .stream().filter(s -> s.stageCode().equals("DEV")).findFirst().orElseThrow();

        assertThat(devOnFastTrack.openTicketCount()).isEqualTo(1);
        assertThat(devOnStandard.openTicketCount()).isZero();
        assertThat(devOnStandard.isCodeEditable()).isTrue();

        removeTicket();
    }

    // ------------------------------------------------------------------
    // writes against the real constraints
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a create lands at the end of the ribbon, at max + 10")
    void aCreateAppends() {
        long template = infraFlow();

        StageDtos.StageView created = service.create(template, new StageDtos.StageWrite(
                "HANDOVER", "Handover", "PM", new BigDecimal("2.50"), true,
                List.of("VERIFY"), "handshake"));

        assertThat(created.seq()).isEqualTo((short) 60);
        assertThat(created.position()).isEqualTo(6);
        assertThat(created.canReturnTo()).containsExactly("VERIFY");
        assertThat(created.isOptional()).isTrue();
    }

    /**
     * {@code ck_can_return_to_is_array} is the one thing the database can still
     * assert once the array becomes an opaque JSON document, and MySQL parsed and
     * silently ignored {@code CHECK} before 8.0.16 — a constraint that does not
     * constrain is worse than none, because it reads as protection.
     */
    @Test
    @DisplayName("ck_can_return_to_is_array refuses a JSON scalar")
    void theArrayCheckIsEnforced() {
        long template = infraFlow();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE workflow_stages SET can_return_to = ? WHERE template_id = ? "
                        + "AND stage_code = 'VERIFY'",
                "\"DEPLOY\"", template))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("uq_workflow_stages_code refuses a duplicate before the service is even asked")
    void theCodeUniqueKeyIsEnforced() {
        long template = infraFlow();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO workflow_stages
                  (template_id, seq, stage_code, display_name, owner_role)
                VALUES (?, 90, 'VERIFY', 'Verification again', 'PM')
                """, template))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("an owner role nothing matches is refused — owner_role has no foreign key")
    void anUnknownOwnerRoleIsRefusedInTheService() {
        // SUPPORT_DESK is the code V20260807_1030 renamed away. The database would
        // accept it happily; only the service will not.
        assertThatThrownBy(() -> service.create(infraFlow(), new StageDtos.StageWrite(
                "HANDOVER", "Handover", "SUPPORT_DESK", null, false, null, null)))
                .isInstanceOf(StageService.StageValidationException.class);

        assertThatCode(() -> jdbc.update("""
                INSERT INTO workflow_stages
                  (template_id, seq, stage_code, display_name, owner_role)
                VALUES (?, 95, 'HANDOVER', 'Handover', 'SUPPORT_DESK')
                """, infraFlow())).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------

    /**
     * A ticket standing in a stage, built from nothing.
     *
     * <p>The migrations seed reference data — roles, task types, statuses,
     * templates — and no {@code users} or {@code projects} rows at all, so the
     * fixture creates both. Written as raw inserts rather than through Stream C's
     * ticket service because what is being proved is which column the usage SQL
     * reads; a ticket created properly would prove that plus a great deal that
     * belongs to another package's tests.
     */
    private void insertTicket(long templateId, String stageCode) {
        Integer roleId = jdbc.queryForObject(
                "SELECT id FROM roles WHERE code = 'DEVELOPER'", Integer.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES ('IT-STAGE', 'it.stage', 'it.stage@example.test', 'x', 'Stage Fixture', ?)
                """, roleId);
        Long userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE emp_code = 'IT-STAGE'", Long.class);

        jdbc.update("INSERT INTO projects (project_code, name) VALUES ('ITS', 'Stage Fixture')");
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM projects WHERE project_code = 'ITS'", Long.class);

        Integer taskTypeId = jdbc.queryForObject("SELECT MIN(id) FROM task_types", Integer.class);

        jdbc.update("""
                INSERT INTO tickets
                  (ticket_code, project_id, task_type_id, title, description, level,
                   original_level, status, reported_by, workflow_template_id, current_stage,
                   stage_entered_at)
                VALUES ('ITS-26-00001', ?, ?, 'Stage master fixture', 'x', 'MEDIUM',
                        'MEDIUM', 'IN_PROGRESS', ?, ?, ?, UTC_TIMESTAMP(6))
                """, projectId, taskTypeId, userId, templateId, stageCode);
    }

    /** Undo {@link #insertTicket}, innermost first — every row is behind a foreign key. */
    private void removeTicket() {
        jdbc.update("DELETE FROM tickets WHERE ticket_code = 'ITS-26-00001'");
        jdbc.update("DELETE FROM projects WHERE project_code = 'ITS'");
        jdbc.update("DELETE FROM users WHERE emp_code = 'IT-STAGE'");
    }

    // ------------------------------------------------------------------
    // deprecation — B-042
    // ------------------------------------------------------------------

    /**
     * The migration's own guard, asserted rather than assumed.
     *
     * <p>MySQL enforces {@code CHECK} from 8.0.16 and silently parsed-and-ignored
     * it before, which is the behaviour most of the guidance still describes.
     * PLAN.md §3.1 says we rely on the enforcement; this is the row that proves we
     * can. Written through raw SQL because no code path in the service can produce
     * a flag and a timestamp that disagree — the constraint exists for the
     * migration, the console and whatever comes next.
     */
    @Test
    @DisplayName("the flag and the timestamp cannot disagree — ck_workflow_stages_deprecation is enforced")
    void deprecationCheckIsEnforced() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE workflow_stages SET is_deprecated = 1, deprecated_at = NULL "
                        + "WHERE template_id = ? AND stage_code = 'CLOSED'", standardDevFlow()))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE workflow_stages SET is_deprecated = 0, deprecated_at = NOW(6) "
                        + "WHERE template_id = ? AND stage_code = 'CLOSED'", standardDevFlow()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("a retire round-trips both columns through the real row")
    void deprecationPersists() {
        long template = infraFlow();
        long closed = service.list(template).orElseThrow().stream()
                .filter(v -> v.stageCode().equals("CLOSED")).findFirst().orElseThrow().id();

        service.setDeprecated(template, closed, true);

        // Read as Boolean, and that is the connector rather than a preference:
        // Connector/J maps TINYINT(1) to java.lang.Boolean by default, so the
        // obvious `((Number) …).intValue()` throws ClassCastException against a
        // real database and passes against every mock. Asked for explicitly here
        // so the column's wire type is part of what this test pins.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT is_deprecated, deprecated_at FROM workflow_stages WHERE id = ?", closed);
        assertThat(row.get("is_deprecated")).isEqualTo(Boolean.TRUE);
        assertThat(row.get("deprecated_at")).isNotNull();

        service.setDeprecated(template, closed, false);

        row = jdbc.queryForMap(
                "SELECT is_deprecated, deprecated_at FROM workflow_stages WHERE id = ?", closed);
        assertThat(row.get("is_deprecated")).isEqualTo(Boolean.FALSE);
        assertThat(row.get("deprecated_at")).isNull();
    }

    /**
     * B-004's seed obeys the rule this task adds, asserted rather than trusted —
     * the same call {@code seededReturnTargetsAreAllBackward} makes about the
     * other half of {@code can_return_to}.
     *
     * <p>If any seeded stage arrived deprecated, tab 2 would open showing retired
     * rows nobody retired, and {@code TicketListPage} would drop those codes from
     * S-25's filter on the strength of a migration.
     */
    @Test
    @DisplayName("every seeded stage is live — a retired row in the seed would silently shrink S-25's filter")
    void theSeedIsEntirelyLive() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_stages WHERE is_deprecated = 1", Integer.class))
                .isZero();
    }

    /**
     * The delete against the real table, and the point is what it does <em>not</em>
     * cascade.
     *
     * <p>There is no foreign key from {@code ticket_stage_transitions} onto this
     * table, so nothing in the database would have refused a delete on a used
     * stage — which is why the service refuses it and why this asserts the refusal
     * here rather than only against mocks. What is removed is a stage this test
     * created, on the one path §7.4 leaves open.
     */
    @Test
    @DisplayName("an unused stage is really removed, and a used one is refused by the service rather than the schema")
    void deleteReachesTheRowAndStopsAtTheRule() {
        long template = infraFlow();
        StageDtos.StageView added = service.create(template, new StageDtos.StageWrite(
                "SANDBOX", "Sandbox", "PM", new BigDecimal("2.00"), false, null, null));

        service.delete(template, added.id());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_stages WHERE id = ?", Integer.class, added.id()))
                .isZero();

        // And the one the rule protects. V20260807_1700 gives Infra Flow's VERIFY
        // a loop-back to DEPLOY, so the arrow alone refuses the delete even though
        // no ticket has ever existed — asserted here rather than assumed, because
        // this test read a *missing* arrow as permission to destroy a seeded stage
        // when `restoreSeed` was not putting them back.
        assertThat(service.list(template).orElseThrow())
                .filteredOn(v -> v.stageCode().equals("VERIFY"))
                .flatExtracting(StageDtos.StageView::canReturnTo)
                .containsExactly("DEPLOY");

        long deploy = service.list(template).orElseThrow().stream()
                .filter(v -> v.stageCode().equals("DEPLOY")).findFirst().orElseThrow().id();
        assertThatThrownBy(() -> service.delete(template, deploy))
                .isInstanceOf(StageService.ReturnTargetDirectionException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_stages WHERE id = ?", Integer.class, deploy))
                .isEqualTo(1);
    }

    /**
     * The index the migration adds, asserted for existence rather than for a plan.
     *
     * <p>A missing index is a migration that ran differently from the one that was
     * reviewed, and eighteen rows will never show it in a timing.
     */
    @Test
    @DisplayName("ix_workflow_stages_live exists — the live-stages-of-this-template read")
    void theLiveIndexExists() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() "
                        + "  AND table_name = 'workflow_stages' "
                        + "  AND index_name = 'ix_workflow_stages_live'",
                Integer.class)).isEqualTo(3);
    }
}
