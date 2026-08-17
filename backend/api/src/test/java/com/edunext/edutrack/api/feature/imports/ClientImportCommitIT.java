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
 * B-035 · step 5 end to end, against a real MySQL.
 *
 * <p>{@link ClientImportUpsertIT} proves the upsert is one; this proves the
 * <em>route</em> that reaches it does the whole job — opens a batch, writes the
 * rows the server's own dry run judged writable, moves the counters, and lands
 * on a terminal status a progress bar can stop at.
 *
 * <p>The scenario is §4B.3's own: a file with good rows, a bad row and a
 * duplicate, committed, then the corrected file committed again. The failure
 * this guards against is the silent one — every row "succeeded" and the account
 * now has two of everything.
 *
 * <p>Fixture codes are {@code ITCOM*} so nothing here collides with
 * {@code ITIMP*} or with B-001's seeded content.
 */
@SpringBootTest
@Testcontainers
class ClientImportCommitIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_commit_it")
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
    ImportCommitService commits;

    @Autowired
    ImportBatchService batchReads;

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
        // Clients first — clients.import_batch_id is a foreign key into the
        // table cleared on the next line.
        jdbc.update("DELETE FROM clients WHERE client_code LIKE 'ITCOM%'");
        jdbc.update("DELETE FROM import_batches WHERE file_name = 'commit-it.xlsx'");
    }

    // ── the run ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a commit writes the writable rows and completes")
    void commitsAFile() {
        UUID uploadId = stage(
                row(2, "ITCOMA", "Alpha Industries"),
                row(3, "ITCOMB", "Beta Traders"),
                row(4, "", "No code — rejected"),
                row(5, "ITCOMA", "Duplicate of row 2"));

        ImportDtos.Batch accepted = commits.commit("clients", request(uploadId), null);

        // 202's body: the handle, and every counter at its starting value. The
        // rejected count is already 2 — one bad row and one duplicate — because
        // the dry run knew both before the job started, which is what stops a
        // progress bar jumping at the end.
        assertThat(accepted.total()).isEqualTo(4);
        assertThat(accepted.rejected()).isEqualTo(2);
        assertThat(accepted.errorReportUrl()).as("B-036 has not written one").isNull();

        ImportDtos.Batch finished = awaitTerminal(accepted.batchId());

        assertThat(finished.status()).isEqualTo("COMPLETED");
        assertThat(finished.created()).isEqualTo(2);
        assertThat(finished.updated()).isZero();
        assertThat(finished.rejected()).isEqualTo(2);
        // Derived rather than stored, and it reaches total exactly when the run
        // is over — which is the property the progress bar is built on.
        assertThat(finished.processed()).isEqualTo(finished.total());

        assertThat(fixtures()).hasSize(2);
        assertThat(clients.findByClientCode("ITCOMA")).get()
                .extracting(Client::getName)
                .as("the first row wins the duplicate, exactly as the preview said")
                .isEqualTo("Alpha Industries");
    }

    @Test
    @DisplayName("re-committing a corrected file updates and never duplicates")
    void reUploadingDoesNotDuplicate() {
        // The rule the whole feature is judged on, through the real route rather
        // than by calling upsert directly.
        commits.commit("clients", request(stage(
                row(2, "ITCOMX", "Alpha"),
                row(3, "ITCOMY", "Beta"),
                row(4, "", "Rejected"))), null);
        awaitFixtures(2);

        ImportDtos.Batch second = commits.commit("clients", request(stage(
                row(2, "ITCOMX", "Alpha Renamed"),
                row(3, "ITCOMY", "Beta"),
                row(4, "ITCOMZ", "Now has a code"))), null);
        ImportDtos.Batch finished = awaitTerminal(second.batchId());

        assertThat(finished.updated()).isEqualTo(2);
        assertThat(finished.created()).isEqualTo(1);
        assertThat(fixtures()).as("three clients, not five").hasSize(3);
        assertThat(clients.findByClientCode("ITCOMX")).get()
                .extracting(Client::getName).isEqualTo("Alpha Renamed");
    }

    @Test
    @DisplayName("the batch row records the run, and the client points back at it")
    void theRunIsTraceable() {
        // B-037 reverses a bad import as a set, and this is the link that makes
        // that possible: every client written by a run carries its batch id.
        //
        // A *real* user id, because `imported_by` carries fk_import_batches_user.
        // An invented one is refused by the database — which is the correct
        // behaviour and is worth having found here rather than in production:
        // attribution on this row is a foreign key, not a free-text note.
        Long importer = anExistingUserId();

        ImportDtos.Batch accepted = commits.commit("clients",
                request(stage(row(2, "ITCOMTRACE", "Traceable Ltd"))), importer);
        awaitTerminal(accepted.batchId());

        ImportBatch stored = batches.findById(accepted.batchId()).orElseThrow();
        assertThat(stored.getEntity()).isEqualTo("CLIENT");
        assertThat(stored.getFileName()).isEqualTo("commit-it.xlsx");
        assertThat(stored.getImportedBy()).isEqualTo(importer);
        assertThat(stored.getStatus()).isEqualTo(ImportBatchStatus.COMPLETED);

        assertThat(clients.findByClientCode("ITCOMTRACE")).get()
                .extracting(Client::getImportBatchId).isEqualTo(accepted.batchId());
    }

    /**
     * An unidentifiable caller commits with a null {@code imported_by} rather
     * than being refused — the same trade B-033 made for a preset's
     * {@code created_by}. Attribution is on no key and nothing filters on it, so
     * failing a legitimate import to protect a column nothing reads would be the
     * wrong way round.
     */
    @Test
    @DisplayName("an unidentifiable caller still commits, with no attribution")
    void anAnonymousCallerIsNotRefused() {
        ImportDtos.Batch accepted = commits.commit("clients",
                request(stage(row(2, "ITCOMANON", "Anonymous Ltd"))), null);
        awaitTerminal(accepted.batchId());

        assertThat(batches.findById(accepted.batchId()).orElseThrow().getImportedBy()).isNull();
        assertThat(clients.findByClientCode("ITCOMANON")).isPresent();
    }

    // ── the staged file ─────────────────────────────────────────────────────

    @Test
    @DisplayName("the staged upload is consumed, so the same file cannot be committed twice")
    void consumesTheUpload() {
        UUID uploadId = stage(row(2, "ITCOMONCE", "Once Only"));

        commits.commit("clients", request(uploadId), null);
        awaitFixtures(1);

        // The second press of a button that did not visibly respond, or a retry
        // after a timeout. Refused — and refusing is what stops the file being
        // written twice.
        assertThatThrownBy(() -> commits.commit("clients", request(uploadId), null))
                .isInstanceOf(ImportUploadNotAvailableException.class);
        assertThat(fixtures()).hasSize(1);
    }

    @Test
    @DisplayName("a refused commit leaves the file staged and the database untouched")
    void refusalsChangeNothing() {
        UUID uploadId = stage(row(2, "ITCOMSAFE", "Safe"));

        assertThatThrownBy(() -> commits.commit("clients",
                new ImportDtos.CommitRequest(uploadId, null, Map.of("clientCode", "Client Code"), null),
                null))
                .isInstanceOf(IncompleteMappingException.class);

        assertThat(fixtures()).isEmpty();
        assertThat(batches.findAll().stream()
                .filter(batch -> "commit-it.xlsx".equals(batch.getFileName()))).isEmpty();
        assertThat(staging.find(uploadId)).isPresent();
    }

    @Test
    @DisplayName("a file with nothing writable is refused rather than recorded as an empty run")
    void nothingToCommitLeavesNoBatchRow() {
        UUID uploadId = stage(row(2, "", "No code"));

        assertThatThrownBy(() -> commits.commit("clients", request(uploadId), null))
                .isInstanceOf(NothingToCommitException.class);

        // The audit trail must not carry a run that imported nothing — see
        // NothingToCommitException.
        assertThat(batches.findAll().stream()
                .filter(batch -> "commit-it.xlsx".equals(batch.getFileName()))).isEmpty();
    }

    // ── the poll ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the progress read answers from the table, and its ETag moves with the counters")
    void theProgressReadIsBackedByTheRow() {
        ImportDtos.Batch accepted = commits.commit("clients",
                request(stage(row(2, "ITCOMPOLL", "Polled Ltd"))), null);
        String queuedTag = accepted.etag();

        ImportDtos.Batch finished = awaitTerminal(accepted.batchId());

        assertThat(finished.batchId()).isEqualTo(accepted.batchId());
        assertThat(finished.entity()).isEqualTo("CLIENT");
        // The whole reason the route declares one: a client polling every two
        // seconds transfers a body only when something actually moved.
        assertThat(finished.etag()).isNotEqualTo(queuedTag);
        assertThat(batchReads.find(accepted.batchId()).etag()).isEqualTo(finished.etag());
    }

    @Test
    @DisplayName("an unknown batch id is a 404, not an empty progress reading")
    void unknownBatchIsNotFound() {
        assertThatThrownBy(() -> batchReads.find(9_999_999L))
                .isInstanceOf(ImportBatchNotFoundException.class);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Waits for the background job rather than sleeping for a guessed interval.
     *
     * <p>The commit is genuinely asynchronous here — the real pool, the real
     * runner — which is most of the reason this is an IT at all. A fixed sleep
     * would be either flaky or slow.
     *
     * <p>Hand-rolled rather than through Awaitility, which is not a declared
     * dependency of this project: adding one means editing
     * {@code backend/pom.xml}, and that is Stream A's build file. Eight lines
     * here costs less than a cross-stream sign-off.
     */
    private ImportDtos.Batch awaitTerminal(long batchId) {
        awaitUntil(() -> List.of("COMPLETED", "FAILED")
                .contains(batchReads.find(batchId).status()), "batch " + batchId + " to finish");
        return batchReads.find(batchId);
    }

    private void awaitFixtures(int count) {
        awaitUntil(() -> fixtures().size() == count, count + " fixture client(s) to be written");
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

    /** Whoever B-001's seed put in first — this test cares that the id resolves, not who it is. */
    private Long anExistingUserId() {
        return jdbc.queryForObject("SELECT MIN(id) FROM users", Long.class);
    }

    private List<Client> fixtures() {
        return clients.findAll().stream()
                .filter(client -> client.getClientCode().startsWith("ITCOM"))
                .toList();
    }

    private UUID stage(StagedRow... rows) {
        StagedUpload upload = new StagedUpload(UUID.randomUUID(), "commit-it.xlsx",
                List.of("Clients"), "Clients", List.of("Client Code", "Name"),
                List.of(rows), Instant.now());
        staging.stage(upload);
        return upload.uploadId();
    }

    private static StagedRow row(int number, String code, String name) {
        Map<String, String> cells = new LinkedHashMap<>();
        if (!code.isEmpty()) {
            cells.put("Client Code", code);
        }
        cells.put("Name", name);
        return new StagedRow(number, cells);
    }

    private static ImportDtos.CommitRequest request(UUID uploadId) {
        return new ImportDtos.CommitRequest(uploadId, "Clients",
                Map.of("clientCode", "Client Code", "name", "Name"), null);
    }
}
