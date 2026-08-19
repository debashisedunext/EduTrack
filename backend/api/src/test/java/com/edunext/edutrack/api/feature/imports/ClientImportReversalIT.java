package com.edunext.edutrack.api.feature.imports;

import com.edunext.edutrack.domain.clients.Client;
import com.edunext.edutrack.domain.clients.ClientRepository;
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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * B-037 · <b>a bad import, reversed as a set, against a real MySQL.</b>
 *
 * <p>Blueprint §4B.3's closing validation rule and §17's mitigation for "Client
 * Excel import silently corrupts the master". {@link ImportReversalServiceTest}
 * owns the refusals; this owns the part only a database can prove — that the
 * rows actually go, that the ones which must not go stay, and that the foreign
 * keys around {@code clients} behave the way the design assumes they do.
 *
 * <p><b>The RESTRICT constraints are the reason this test exists.</b>
 * {@code fk_tickets_client}, {@code fk_tickets_client_contact} and
 * {@code fk_client_contacts_client} all default to RESTRICT, and the whole
 * design of {@code ClientImportSchema.reverse} — pre-check the tickets, delete
 * the contacts first, one transaction per client — is a set of claims about
 * them. Every one of those claims is false against a mock.
 *
 * <p>Fixture codes are {@code ITREV*} so nothing here collides with
 * {@code ITCOM*}, {@code ITIMP*} or B-001's seeded content.
 */
@SpringBootTest
@Testcontainers
class ClientImportReversalIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_reversal_it")
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

    /** The report store, in memory — the commit path writes one and there is no MinIO here. */
    @org.springframework.boot.test.context.TestConfiguration
    static class Storage {

        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        ImportReportStore inMemoryImportReportStore() {
            return new InMemoryImportReportStore();
        }
    }

    @Autowired
    ImportCommitService commits;

    @Autowired
    ImportBatchService batchReads;

    @Autowired
    ImportReversalService reversals;

    @Autowired
    ImportStagingStore staging;

    @Autowired
    ClientRepository clients;

    @Autowired
    ImportBatchRepository batches;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clearFixtureRows() {
        // Tickets first, then contacts, then clients, then batches — the reverse
        // of the reference order. Cleaning up in the wrong order fails on the
        // very constraints this test is about.
        jdbc.update("DELETE FROM tickets WHERE ticket_code LIKE 'ITREV%'");
        jdbc.update("DELETE FROM client_contacts WHERE client_id IN"
                + " (SELECT id FROM clients WHERE client_code LIKE 'ITREV%')");
        jdbc.update("DELETE FROM clients WHERE client_code LIKE 'ITREV%'");
        jdbc.update("DELETE FROM import_batches WHERE file_name = 'reversal-it.xlsx'");
    }

    // ── the happy path ──────────────────────────────────────────────────────

    /**
     * The whole promise in one test: import three clients, decide it was the
     * wrong file, take them back.
     */
    @Test
    @DisplayName("a reversal deletes every client the run created")
    void reversesTheSet() {
        long batchId = importAndAwait(
                row(2, "ITREVA", "Alpha"),
                row(3, "ITREVB", "Beta"),
                row(4, "ITREVC", "Gamma"));

        assertThat(fixtures()).hasSize(3);

        ImportDtos.Reversal result = reversals.reverse(batchId, null);

        assertThat(result.deleted()).containsExactlyInAnyOrder("ITREVA", "ITREVB", "ITREVC");
        assertThat(result.retained()).isEmpty();
        assertThat(fixtures()).as("the set is gone").isEmpty();
    }

    /**
     * <b>The batch row survives, and it is the point of the feature.</b> It is
     * the audit trail — a reversal that erased its own record would leave the
     * master short of rows with nothing anywhere explaining why.
     */
    @Test
    @DisplayName("the batch row survives the reversal, with the reversal recorded on it")
    void theBatchRowIsTheAuditTrail() {
        long batchId = importAndAwait(row(2, "ITREVKEEP", "Kept Record Ltd"));
        long actor = anImporter();

        reversals.reverse(batchId, actor);

        ImportBatch stored = batches.findById(batchId).orElseThrow();
        assertThat(stored.getReversedAt()).isNotNull();
        assertThat(stored.getReversedBy()).isEqualTo(actor);
        assertThat(stored.getReversedRows()).isEqualTo(1);
        assertThat(stored.getRetainedRows()).isZero();
        // The run's own outcome is untouched. There is no REVERSED status on
        // purpose: overwriting this would collapse "how the run ended" into "what
        // happened to it later", and the two are independent facts.
        assertThat(stored.getStatus()).isEqualTo(ImportBatchStatus.COMPLETED);
        assertThat(stored.getFileName()).isEqualTo("reversal-it.xlsx");
    }

    // ── what a reversal must not touch ──────────────────────────────────────

    /**
     * <b>The property the whole operation is safe because of.</b>
     * {@code import_batch_id} is stamped on insert only, so a client that existed
     * before the run and was merely corrected by it is not attributed to the run
     * and is not deleted with it.
     *
     * <p>Without this, a spreadsheet that created 12 clients and fixed the phone
     * numbers of 400 would reverse to 412 clients removed from the master.
     */
    @Test
    @DisplayName("a client the run only updated is left alone — it belongs to no batch of this run")
    void updatesAreNotDeleted() {
        long first = importAndAwait(row(2, "ITREVOLD", "Original Name"));
        long second = importAndAwait(
                row(2, "ITREVOLD", "Corrected Name"),
                row(3, "ITREVNEW", "Brand New Ltd"));

        assertThat(batchReads.find(second).updated()).isEqualTo(1);
        assertThat(batchReads.find(second).created()).isEqualTo(1);

        ImportDtos.Reversal result = reversals.reverse(second, null);

        assertThat(result.deleted()).containsExactly("ITREVNEW");
        // And the number that makes the promise honest on the wire.
        assertThat(result.updatedRowsNotReverted()).isEqualTo(1);

        assertThat(clients.findByClientCode("ITREVOLD"))
                .as("still there, and still attributed to the run that created it")
                .get()
                .satisfies(client -> {
                    assertThat(client.getImportBatchId()).isEqualTo(first);
                    // Not restored either — there is no before image, which is
                    // exactly why the response says so rather than implying it.
                    assertThat(client.getName()).isEqualTo("Corrected Name");
                });
    }

    /**
     * <b>The RESTRICT constraint, and the reason a reversal is allowed to be
     * partial.</b> A client that has been named on a ticket since the import
     * cannot be deleted, and should not be: the two ways to force the count to
     * zero are failing the whole reversal or destroying the ticket's client.
     */
    @Test
    @DisplayName("a client with a ticket raised since the import is kept, and named")
    void aTicketedClientIsRetained() {
        long batchId = importAndAwait(
                row(2, "ITREVFREE", "Untouched Ltd"),
                row(3, "ITREVUSED", "Working With Us Ltd"));

        long usedId = clients.findByClientCode("ITREVUSED").orElseThrow().getId();
        raiseTicketAgainst(usedId);

        ImportDtos.Reversal result = reversals.reverse(batchId, null);

        assertThat(result.deleted()).containsExactly("ITREVFREE");
        assertThat(result.retained())
                .singleElement()
                .satisfies(kept -> {
                    assertThat(kept.naturalKey()).isEqualTo("ITREVUSED");
                    // A sentence a person can act on, not a constraint name.
                    assertThat(kept.reason()).contains("1 ticket");
                });

        assertThat(clients.findByClientCode("ITREVUSED")).isPresent();
        assertThat(clients.findByClientCode("ITREVFREE")).isEmpty();

        // Recorded permanently, because it is not derivable afterwards: once the
        // others are gone, an unreversed batch and a fully reversed one both
        // count zero.
        ImportBatch stored = batches.findById(batchId).orElseThrow();
        assertThat(stored.getReversedRows()).isEqualTo(1);
        assertThat(stored.getRetainedRows()).isEqualTo(1);
    }

    /**
     * The line the feature draws between a contact and a ticket.
     *
     * <p>{@code fk_client_contacts_client} is RESTRICT, so a client with contacts
     * cannot simply be deleted — and every imported client is likely to acquire
     * one, because §4B.2 requires a primary contact before the client can be
     * chosen on a ticket. A contact is wholly owned by its client and means
     * nothing without one, so it goes with it. A ticket is independent work, so
     * it keeps the client instead.
     */
    @Test
    @DisplayName("a client's contacts are deleted with it, where a ticket would have kept it")
    void contactsGoWithTheClient() {
        long batchId = importAndAwait(row(2, "ITREVCONT", "Has Contacts Ltd"));
        long clientId = clients.findByClientCode("ITREVCONT").orElseThrow().getId();
        addContact(clientId, "Priya Nair");
        addContact(clientId, "Ravi Kumar");

        assertThat(contactCount(clientId)).isEqualTo(2);

        ImportDtos.Reversal result = reversals.reverse(batchId, null);

        assertThat(result.deleted()).containsExactly("ITREVCONT");
        assertThat(clients.findByClientCode("ITREVCONT")).isEmpty();
        assertThat(contactCount(clientId)).as("no orphaned contacts").isZero();
    }

    // ── running it twice ────────────────────────────────────────────────────

    /**
     * Refused rather than quietly succeeding. The second call would delete
     * nothing, and would overwrite {@code reversed_at} and both counters with its
     * own zeroes — a row claiming it was reversed just now, deleting nothing,
     * which is a false entry in the table that exists to make bad imports
     * traceable.
     */
    @Test
    @DisplayName("a second reversal is refused, and the first one's record is intact")
    void secondReversalIsRefused() {
        long batchId = importAndAwait(row(2, "ITREVONCE", "Once Only Ltd"));
        reversals.reverse(batchId, null);
        Instant firstReversal = batches.findById(batchId).orElseThrow().getReversedAt();

        assertThatThrownBy(() -> reversals.reverse(batchId, null))
                .isInstanceOf(ImportBatchAlreadyReversedException.class);

        ImportBatch stored = batches.findById(batchId).orElseThrow();
        assertThat(stored.getReversedAt()).isEqualTo(firstReversal);
        assertThat(stored.getReversedRows()).isEqualTo(1);
    }

    // ── the history ─────────────────────────────────────────────────────────

    /**
     * The identification half of the promise, against real rows: a run committed
     * a moment ago is findable without knowing its id, which is exactly what a
     * user who closed the wizard does not have.
     */
    @Test
    @DisplayName("the history lists the run, newest first, with its provenance")
    void theHistoryFindsTheRun() {
        long actor = anImporter();
        long batchId = importAndAwait(actor, row(2, "ITREVHIST", "Findable Ltd"));

        ImportDtos.BatchList history = batchReads.history("CLIENT");

        assertThat(history.entity()).isEqualTo("CLIENT");
        assertThat(history.limit()).isEqualTo(ImportBatchService.HISTORY_LIMIT);
        assertThat(history.batches())
                .filteredOn(batch -> batch.batchId() == batchId)
                .singleElement()
                .satisfies(batch -> {
                    assertThat(batch.fileName()).isEqualTo("reversal-it.xlsx");
                    assertThat(batch.startedAt()).isNotNull();
                    assertThat(batch.importedBy()).isEqualTo(actor);
                    assertThat(batch.importedByName()).isEqualTo("Reversal Fixture");
                    assertThat(batch.reversible()).isTrue();
                });
    }

    @Test
    @DisplayName("a resource-entity read does not return client runs")
    void historyIsPerEntity() {
        importAndAwait(row(2, "ITREVENT", "Client Entity Ltd"));

        assertThat(batchReads.history("RESOURCE").batches())
                .as("RESOURCE is registered since B-038, and its runs are not this one's")
                .isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private long importAndAwait(StagedRow... rows) {
        return importAndAwait(null, rows);
    }

    private long importAndAwait(Long actor, StagedRow... rows) {
        ImportDtos.Batch accepted = commits.commit("clients", request(stage(rows)), actor);
        awaitUntil(() -> List.of("COMPLETED", "FAILED")
                        .contains(batchReads.find(accepted.batchId()).status()),
                "batch " + accepted.batchId() + " to finish");
        return accepted.batchId();
    }

    /**
     * A ticket naming this client, inserted directly.
     *
     * <p>Through JDBC rather than through Stream C's service: this test needs a
     * row that trips {@code fk_tickets_client}, and nothing about how a ticket is
     * created matters to it. Going through the ticket API would make a Stream B
     * test fail whenever Stream C changed a required field.
     */
    private void raiseTicketAgainst(long clientId) {
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, level, original_level,
                                     status, client_id)
                VALUES ('ITREV-TKT-1', ?, 'Reversal fixture', 'LOW', 'LOW', 'NEW', ?)
                """, aProject(), clientId);
    }

    private void addContact(long clientId, String name) {
        jdbc.update("INSERT INTO client_contacts (client_id, name, is_primary) VALUES (?, ?, 0)",
                clientId, name);
    }

    private int contactCount(long clientId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM client_contacts WHERE client_id = ?", Integer.class, clientId);
        return count == null ? 0 : count;
    }

    /**
     * An importer to attribute a run to, created here rather than looked up.
     *
     * <p><b>No migration seeds {@code users}.</b> The Flyway set builds the
     * schema and seeds reference data — roles, permissions, statuses, task types
     * — and people arrive through the application. So {@code SELECT MIN(id) FROM
     * users} on a fresh container answers null, and a test that took that for an
     * id would silently assert nothing: {@code import_batches.imported_by} is
     * nullable, so the write succeeds and every attribution assertion passes
     * against a null.
     *
     * <p>{@code role_id} is a foreign key and the roles <em>are</em> seeded
     * (B-001), so that one is looked up. The rest are the table's own NOT NULL
     * columns with values that make it obvious in a dump where they came from.
     */
    private long anImporter() {
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                SELECT 'ITREV-1', 'itrev.importer', 'itrev@example.test', 'x', 'Reversal Fixture',
                       (SELECT MIN(id) FROM roles)
                WHERE NOT EXISTS (SELECT 1 FROM users WHERE emp_code = 'ITREV-1')
                """);
        return jdbc.queryForObject(
                "SELECT id FROM users WHERE emp_code = 'ITREV-1'", Long.class);
    }

    /** A project for the ticket fixture to hang off, for the same reason. */
    private long aProject() {
        jdbc.update("""
                INSERT INTO projects (project_code, name)
                SELECT 'ITREV', 'Reversal Fixture Project'
                WHERE NOT EXISTS (SELECT 1 FROM projects WHERE project_code = 'ITREV')
                """);
        return jdbc.queryForObject(
                "SELECT id FROM projects WHERE project_code = 'ITREV'", Long.class);
    }

    private List<Client> fixtures() {
        return clients.findAll().stream()
                .filter(client -> client.getClientCode().startsWith("ITREV"))
                .toList();
    }

    private UUID stage(StagedRow... rows) {
        StagedUpload upload = new StagedUpload(UUID.randomUUID(), "reversal-it.xlsx",
                List.of("Clients"), "Clients", List.of("Client Code", "Name"),
                List.of(rows), Instant.now());
        staging.stage(upload);
        return upload.uploadId();
    }

    private static StagedRow row(int number, String code, String name) {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put("Client Code", code);
        cells.put("Name", name);
        return new StagedRow(number, cells);
    }

    private static ImportDtos.CommitRequest request(UUID uploadId) {
        return new ImportDtos.CommitRequest(uploadId, "Clients",
                Map.of("clientCode", "Client Code", "name", "Name"), null);
    }

    private static void awaitUntil(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for " + what);
            }
        }
        fail("Timed out after 30s waiting for " + what);
    }
}
