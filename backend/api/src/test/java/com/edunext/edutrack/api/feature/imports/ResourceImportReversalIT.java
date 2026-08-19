package com.edunext.edutrack.api.feature.imports;

import com.edunext.edutrack.api.feature.imports.schemas.ResourceImportSchema;
import com.edunext.edutrack.domain.imports.ImportBatch;
import com.edunext.edutrack.domain.imports.ImportBatchRepository;
import com.edunext.edutrack.domain.imports.ImportBatchStatus;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-038 · <b>a bad resource import, taken back — and the parts of it that
 * cannot be.</b>
 *
 * <p>Blueprint §4B.3's closing rule and §17's mitigation, applied to the second
 * registration. B-037 put {@code reverse} on the SPI so this task would get the
 * refusals, the counters and the audit trail without writing any of them; what
 * this file asserts is the half only a registration can supply, and every claim
 * in it is a claim about a foreign key — all of which are true against a mock.
 *
 * <h2>Why this is a longer file than the client one</h2>
 *
 * <p>{@code clients} has two inbound foreign keys worth reasoning about.
 * {@code users} has around forty, and the interesting question is not "does the
 * delete work" but <b>where the line falls</b> between a row that goes with the
 * account and one that keeps it alive. Getting that line wrong in one direction
 * makes every reversal fail; in the other it destroys somebody's work to tidy up
 * a spreadsheet. So the cases below are chosen to sit on either side of it.
 */
@SpringBootTest
@Testcontainers
class ResourceImportReversalIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_resource_reversal_it")
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
    ResourceImportSchema schema;

    @Autowired
    ImportValidationEngine engine;

    @Autowired
    ImportReversalService reversals;

    @Autowired
    ImportBatchService batchReads;

    @Autowired
    ImportBatchRepository batches;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clearFixtureRows() {
        // The reverse of the reference order. Cleaning up in the wrong order
        // fails on the very constraints this suite is about.
        jdbc.update("DELETE FROM tickets WHERE ticket_code LIKE 'ITRREV%'");
        jdbc.update("UPDATE projects SET manager_id = NULL WHERE project_code = 'ITRREV'");
        jdbc.update("UPDATE users SET reporting_manager_id = NULL WHERE emp_code LIKE 'ITRREV%'");
        jdbc.update("DELETE FROM project_members WHERE user_id IN"
                + " (SELECT id FROM users WHERE emp_code LIKE 'ITRREV%')");
        jdbc.update("DELETE FROM resource_leaves WHERE user_id IN"
                + " (SELECT id FROM users WHERE emp_code LIKE 'ITRREV%')");
        jdbc.update("DELETE FROM users WHERE emp_code LIKE 'ITRREV%'");
        jdbc.update("DELETE FROM import_batches WHERE file_name = 'resource-reversal-it.xlsx'");
    }

    // ── the happy path ──────────────────────────────────────────────────────

    /**
     * The whole promise in one test: onboard three people from the wrong file,
     * take them back.
     */
    @Test
    @DisplayName("a reversal deletes every resource the run created")
    void reversesTheSet() {
        long batchId = importAndFinish(
                joiner(2, "ITRREVA", "Asha One", "asha.one", "a1@example.test"),
                joiner(3, "ITRREVB", "Bhavin Two", "bhavin.two", "b2@example.test"),
                joiner(4, "ITRREVC", "Chitra Three", "chitra.three", "c3@example.test"));

        assertThat(fixtureCount()).isEqualTo(3);

        ImportDtos.Reversal result = reversals.reverse(batchId, null);

        assertThat(result.deleted()).containsExactlyInAnyOrder("ITRREVA", "ITRREVB", "ITRREVC");
        assertThat(result.retained()).isEmpty();
        assertThat(fixtureCount()).as("the set is gone").isZero();
    }

    /**
     * The batch row survives with the reversal recorded on it — the audit trail
     * that makes an import traceable after the screen that ran it is closed.
     *
     * <p>Asserted here as well as in the client suite because the counters are
     * written by {@code ImportReversalService} from what <em>this</em>
     * registration returns: a {@code reverse} that reported the wrong shape would
     * leave the shared service recording a confident falsehood.
     */
    @Test
    @DisplayName("the batch row survives, with this registration's counts on it")
    void theBatchRowIsTheAuditTrail() {
        long batchId = importAndFinish(
                joiner(2, "ITRREVKEEP", "Kept Record", "kept.record", "keep@example.test"));

        reversals.reverse(batchId, null);

        ImportBatch stored = batches.findById(batchId).orElseThrow();
        assertThat(stored.getReversedAt()).isNotNull();
        assertThat(stored.getReversedRows()).isEqualTo(1);
        assertThat(stored.getRetainedRows()).isZero();
        assertThat(stored.getEntity()).isEqualTo("RESOURCE");
        // There is no REVERSED status on purpose: how the run ended and what
        // happened to it later are independent facts.
        assertThat(stored.getStatus()).isEqualTo(ImportBatchStatus.COMPLETED);
    }

    /**
     * <b>The property the whole operation is safe because of.</b>
     * {@code import_batch_id} is stamped on insert only — this task's migration
     * added the column for exactly this — so somebody who already worked here and
     * had their department corrected by the run is not attributed to it.
     */
    @Test
    @DisplayName("a resource the run only updated is left alone")
    void updatesAreNotDeleted() {
        long first = importAndFinish(
                joiner(2, "ITRREVOLD", "Original Name", "orig.name", "old@example.test"));
        long second = importAndFinish(
                joiner(2, "ITRREVOLD", "Corrected Name", "orig.name", "old@example.test"),
                joiner(3, "ITRREVNEW", "Brand New", "brand.new", "new@example.test"));

        ImportDtos.Reversal result = reversals.reverse(second, null);

        assertThat(result.deleted()).containsExactly("ITRREVNEW");
        assertThat(result.updatedRowsNotReverted()).isEqualTo(1);
        assertThat(rowOf("ITRREVOLD"))
                .as("still there, and still attributed to the run that created them")
                .containsEntry("import_batch_id", first)
                // Not restored either — there is no before image, which is
                // exactly why the response says so rather than implying it.
                .containsEntry("full_name", "Corrected Name");
    }

    // ── where the line falls ────────────────────────────────────────────────

    /**
     * <b>A ticket keeps its person.</b>
     *
     * <p>{@code fk_tickets_reported_by} is RESTRICT, and the two ways to force
     * the count to zero are failing the whole reversal or deleting a ticket's
     * reporter. Both are worse than keeping one row and saying so.
     *
     * <p>The reason is asserted for its <em>content</em>, not merely its
     * presence: "something still references this resource" is not a sentence
     * anybody can act on, and naming the count is the difference between an
     * operator going to look and an operator filing a bug.
     */
    @Test
    @DisplayName("a resource named on a ticket raised since the import is kept, and named")
    void aTicketedResourceIsRetained() {
        long batchId = importAndFinish(
                joiner(2, "ITRREVFREE", "Untouched Person", "untouched.p", "free@example.test"),
                joiner(3, "ITRREVUSED", "Working Person", "working.p", "used@example.test"));

        raiseTicketReportedBy(idOf("ITRREVUSED"));

        ImportDtos.Reversal result = reversals.reverse(batchId, null);

        assertThat(result.deleted()).containsExactly("ITRREVFREE");
        assertThat(result.retained()).singleElement().satisfies(kept -> {
            assertThat(kept.naturalKey()).isEqualTo("ITRREVUSED");
            assertThat(kept.reason()).contains("1 ticket");
        });
        assertThat(exists("ITRREVUSED")).isTrue();
        assertThat(exists("ITRREVFREE")).isFalse();

        // Recorded permanently, because neither number is derivable afterwards.
        ImportBatch stored = batches.findById(batchId).orElseThrow();
        assertThat(stored.getReversedRows()).isEqualTo(1);
        assertThat(stored.getRetainedRows()).isEqualTo(1);
    }

    /**
     * <b>A reporting line keeps its manager.</b>
     *
     * <p>{@code fk_users_manager} is RESTRICT and self-referential. The import
     * cannot set a reporting manager — the registration refuses the column
     * because B-012's cycle rule cannot be enforced row-at-a-time — so the only
     * way to get here is an Admin editing S-08 after the import, which is exactly
     * the "work that has happened since" the check is about.
     */
    @Test
    @DisplayName("a resource somebody was hung under is kept, and told why")
    void aManagerWithReportsIsRetained() {
        long batchId = importAndFinish(
                joiner(2, "ITRREVMGR", "The Manager", "the.manager", "mgr@example.test"));
        long subordinate = anExistingPerson("ITRREVSUB", "The Subordinate", "the.sub");
        jdbc.update("UPDATE users SET reporting_manager_id = ? WHERE id = ?",
                idOf("ITRREVMGR"), subordinate);

        ImportDtos.Reversal result = reversals.reverse(batchId, null);

        assertThat(result.deleted()).isEmpty();
        assertThat(result.retained()).singleElement().satisfies(kept -> {
            assertThat(kept.naturalKey()).isEqualTo("ITRREVMGR");
            assertThat(kept.reason()).contains("1 resource reports to this one");
        });
        assertThat(exists("ITRREVMGR")).isTrue();
    }

    /**
     * <b>A project keeps its manager.</b> {@code fk_projects_manager} is RESTRICT,
     * and a project without its manager is not a state the master should reach by
     * way of undoing an import.
     */
    @Test
    @DisplayName("a resource managing a project is kept, and told why")
    void aProjectManagerIsRetained() {
        long batchId = importAndFinish(
                joiner(2, "ITRREVPM", "The PM", "the.pm", "pm@example.test"));
        jdbc.update("UPDATE projects SET manager_id = ? WHERE id = ?",
                idOf("ITRREVPM"), aProject());

        ImportDtos.Reversal result = reversals.reverse(batchId, null);

        assertThat(result.retained()).singleElement().satisfies(kept ->
                assertThat(kept.reason()).contains("manages 1 project"));
        assertThat(exists("ITRREVPM")).isTrue();
    }

    /**
     * <b>The line the feature draws, in the other direction.</b>
     *
     * <p>A project membership and a leave record are RESTRICT too — so without a
     * statement for each of them a reversal would keep every person who had been
     * put on a project, which is most of a joiner list. They are deleted with the
     * account because neither is a fact about anybody else: a membership is this
     * person's access to a project, and a leave record is days this person was
     * away. Compare the ticket above, which is work that outlives them.
     */
    @Test
    @DisplayName("memberships and leave go with the account, where a ticket would have kept it")
    void ownedRowsGoWithTheAccount() {
        long batchId = importAndFinish(
                joiner(2, "ITRREVOWN", "Owned Rows", "owned.rows", "own@example.test"));
        long userId = idOf("ITRREVOWN");
        jdbc.update("INSERT INTO project_members (project_id, user_id) VALUES (?, ?)",
                aProject(), userId);
        jdbc.update("""
                INSERT INTO resource_leaves (user_id, start_date, end_date, leave_type)
                VALUES (?, '2026-09-01', '2026-09-03', 'PLANNED')
                """, userId);

        ImportDtos.Reversal result = reversals.reverse(batchId, null);

        assertThat(result.deleted()).containsExactly("ITRREVOWN");
        assertThat(exists("ITRREVOWN")).isFalse();
        assertThat(countWhere("project_members", userId)).isZero();
        assertThat(countWhere("resource_leaves", userId)).isZero();
    }

    /**
     * <b>One person that cannot go does not cost the set.</b>
     *
     * <p>The same shape {@code ImportCommitRunner} uses on the way in, and the
     * reason each delete is its own transaction: a single transaction over a
     * joiner list would be rolled back whole by one person who was assigned a
     * ticket while the operator was reading the history panel, turning the most
     * ordinary partial case into total failure.
     */
    @Test
    @DisplayName("a partial reversal keeps going rather than failing whole")
    void onePinnedRowDoesNotCostTheRest() {
        long batchId = importAndFinish(
                joiner(2, "ITRREVP1", "Partial One", "partial.one", "p1@example.test"),
                joiner(3, "ITRREVP2", "Partial Two", "partial.two", "p2@example.test"),
                joiner(4, "ITRREVP3", "Partial Three", "partial.three", "p3@example.test"));

        raiseTicketReportedBy(idOf("ITRREVP2"));

        ImportDtos.Reversal result = reversals.reverse(batchId, null);

        assertThat(result.deleted()).containsExactlyInAnyOrder("ITRREVP1", "ITRREVP3");
        assertThat(result.retained()).hasSize(1);
    }

    // ── the history, per entity ─────────────────────────────────────────────

    /**
     * The identification half of the promise: a resource run is findable without
     * knowing its id, and it does not appear among the client runs.
     *
     * <p>{@code ImportBatch.entity} is the stored discriminator rather than the
     * URL segment, and this is what that separation buys — one panel component,
     * two histories, no filtering in the browser.
     */
    @Test
    @DisplayName("resource runs are listed under RESOURCE and not under CLIENT")
    void theHistoryIsPerEntity() {
        long batchId = importAndFinish(
                joiner(2, "ITRREVHIST", "Findable Person", "findable.p", "hist@example.test"));

        assertThat(batchReads.history("RESOURCE").batches())
                .filteredOn(batch -> batch.batchId() == batchId)
                .singleElement()
                .satisfies(batch -> {
                    assertThat(batch.fileName()).isEqualTo("resource-reversal-it.xlsx");
                    assertThat(batch.reversible()).isTrue();
                });

        assertThat(batchReads.history("CLIENT").batches())
                .filteredOn(batch -> batch.batchId() == batchId)
                .isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Commits the rows the way the job does, then finishes the batch.
     *
     * <p>Deliberately not through {@code ImportCommitService}: the reversal is
     * refused for a run that has not finished, so what this needs is a completed
     * batch with rows attributed to it, and driving the background pool to get
     * one would make every assertion below wait on a thread it is not testing.
     * The client suite drives the real commit; this one is about what happens
     * afterwards.
     */
    private long importAndFinish(ImportRow... rows) {
        ImportBatch batch = new ImportBatch();
        batch.setEntity(schema.entityCode());
        batch.setFileName("resource-reversal-it.xlsx");
        batch.setStatus(ImportBatchStatus.RUNNING);
        long batchId = batches.save(batch).getId();

        ImportPreview preview = engine.validate(schema, List.of(rows));
        preview.writable().forEach(verdict ->
                schema.upsert(new ImportRow(verdict.rowNumber(), verdict.values()), batchId));

        ImportBatch finished = batches.findById(batchId).orElseThrow();
        finished.setStatus(ImportBatchStatus.COMPLETED);
        finished.setTotalRows(rows.length);
        finished.setCreatedRows(preview.willCreate());
        finished.setUpdatedRows(preview.willUpdate());
        batches.save(finished);
        return batchId;
    }

    /**
     * A ticket naming this resource as its reporter, inserted directly.
     *
     * <p>Through JDBC rather than through Stream C's service: this test needs a
     * row that trips {@code fk_tickets_reported_by}, and nothing about how a
     * ticket is created matters to it. Going through the ticket API would make a
     * Stream B test fail whenever Stream C changed a required field.
     */
    private void raiseTicketReportedBy(long userId) {
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, level, original_level,
                                     status, reported_by)
                VALUES (?, ?, 'Reversal fixture', 'LOW', 'LOW', 'NEW', ?)
                """, "ITRREV-TKT-" + userId, aProject(), userId);
    }

    /**
     * Somebody who already worked here — created outside any import, so they
     * carry no {@code import_batch_id} and no reversal will ever touch them.
     */
    private long anExistingPerson(String empCode, String fullName, String username) {
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                SELECT ?, ?, ?, 'x', ?, (SELECT MIN(id) FROM roles)
                WHERE NOT EXISTS (SELECT 1 FROM users WHERE emp_code = ?)
                """, empCode, username, username + "@example.test", fullName, empCode);
        return idOf(empCode);
    }

    /** A project for the fixtures to hang off. No migration seeds one. */
    private long aProject() {
        jdbc.update("""
                INSERT INTO projects (project_code, name)
                SELECT 'ITRREV', 'Resource Reversal Fixture Project'
                WHERE NOT EXISTS (SELECT 1 FROM projects WHERE project_code = 'ITRREV')
                """);
        return jdbc.queryForObject(
                "SELECT id FROM projects WHERE project_code = 'ITRREV'", Long.class);
    }

    private static ImportRow joiner(int rowNumber, String empCode, String fullName,
                                    String username, String email) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("employeeCode", empCode);
        values.put("fullName", fullName);
        values.put("username", username);
        values.put("email", email);
        values.put("role", "DEVELOPER");
        return new ImportRow(rowNumber, values);
    }

    private long idOf(String empCode) {
        return jdbc.queryForObject("SELECT id FROM users WHERE emp_code = ?", Long.class, empCode);
    }

    private boolean exists(String empCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE emp_code = ?", Integer.class, empCode);
        return count != null && count > 0;
    }

    private int fixtureCount() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE emp_code LIKE 'ITRREV%'", Integer.class);
        return count == null ? 0 : count;
    }

    private Map<String, Object> rowOf(String empCode) {
        return jdbc.queryForMap("SELECT * FROM users WHERE emp_code = ?", empCode);
    }

    private int countWhere(String table, long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }
}
