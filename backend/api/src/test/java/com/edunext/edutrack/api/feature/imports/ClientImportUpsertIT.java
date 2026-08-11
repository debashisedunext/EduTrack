package com.edunext.edutrack.api.feature.imports;

import com.edunext.edutrack.api.feature.imports.schemas.ClientImportSchema;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-030 · the upsert rule, against a real MySQL.
 *
 * <p>Blueprint §4B.3: "existing records <b>updated, never duplicated</b> (upsert
 * on client code)". {@link ImportValidationEngineTest} proves the engine reaches
 * the right verdict against a fake schema; this proves the verdict is true of
 * the actual table — which is where it can be wrong in ways a fake cannot show:
 * the collation deciding whether {@code acme} finds {@code ACME}, the unique
 * index, and the batched probe returning codes in their stored case rather than
 * the file's.
 *
 * <p><b>The scenario is the one that actually happens.</b> A user imports 4
 * clients, 1 is rejected, they fix that row and re-upload the whole file. The
 * failure this guards against is ending with 7 clients instead of 4 — and it is
 * silent, because every row "succeeded".
 *
 * <p>Fixture codes are {@code ITIMP*} so nothing here collides with B-001's
 * seeded content or another suite's rows.
 */
@SpringBootTest
@Testcontainers
class ClientImportUpsertIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_import_it")
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
    ClientImportSchema schema;

    @Autowired
    ImportValidationEngine engine;

    @Autowired
    ImportSchemaRegistry registry;

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
        jdbc.update("DELETE FROM clients WHERE client_code LIKE 'ITIMP%'");
        jdbc.update("DELETE FROM import_batches WHERE file_name IN ('clients.xlsx', 'defaulted.xlsx')");
    }

    // ── the registry, wired for real ────────────────────────────────────────

    @Test
    @DisplayName("the client schema is registered under the contract's own path segment")
    void isRegisteredUnderTheContractKey() {
        // contracts/openapi.yaml constrains {schema} to [clients, users]. The
        // generated TypeScript client already calls /imports/clients/... — if
        // this key drifts, the wizard 404s on a path the frontend cannot change.
        assertThat(registry.resolve("clients")).isSameAs(schema);
        assertThat(schema.entityCode()).isEqualTo("CLIENT");
    }

    // ── the rule the feature is judged on ───────────────────────────────────

    @Test
    @DisplayName("re-uploading a corrected file updates and never duplicates")
    void reUploadingACorrectedFileDoesNotDuplicate() {
        ImportBatch first = batch();

        // Run 1 — one row is rejected for a missing name.
        List<ImportRow> original = List.of(
                row(2, "ITIMPA", "Alpha Industries", "alpha@example.com"),
                row(3, "ITIMPB", "Beta Traders", "beta@example.com"),
                row(4, "ITIMPC", null, "gamma@example.com"),
                row(5, "ITIMPD", "Delta Services", "delta@example.com"));

        ImportPreview preview = engine.validate(schema, original);
        assertThat(preview.willCreate()).isEqualTo(3);
        assertThat(preview.rejected()).isEqualTo(1);
        commit(preview, first.getId());

        assertThat(clients.findAll().stream().filter(this::isFixture)).hasSize(3);

        // Run 2 — the user fixes row 4 and re-uploads the whole file, which is
        // what people do. Three rows are unchanged and must not be inserted again.
        ImportBatch second = batch();
        List<ImportRow> corrected = List.of(
                row(2, "ITIMPA", "Alpha Industries", "alpha@example.com"),
                row(3, "ITIMPB", "Beta Traders", "beta@example.com"),
                row(4, "ITIMPC", "Gamma Holdings", "gamma@example.com"),
                row(5, "ITIMPD", "Delta Services", "delta@example.com"));

        ImportPreview rerun = engine.validate(schema, corrected);
        assertThat(rerun.willUpdate()).isEqualTo(3);
        assertThat(rerun.willCreate()).isEqualTo(1);
        assertThat(rerun.rejected()).isZero();
        commit(rerun, second.getId());

        assertThat(clients.findAll().stream().filter(this::isFixture))
                .as("four clients, not seven")
                .hasSize(4);
        assertThat(clients.findByClientCode("ITIMPC")).get()
                .extracting(Client::getName).isEqualTo("Gamma Holdings");
    }

    @Test
    @DisplayName("a differently-cased code finds the existing client, not a second one")
    void matchesAcrossCase() {
        // clients.client_code collates utf8mb4_0900_ai_ci, so uq_clients_code
        // treats these as one row. If the engine disagreed with the index, the
        // dry run would promise a create and the commit would hit a duplicate
        // key — the worst outcome, halfway through a batch.
        ImportBatch first = batch();
        commit(engine.validate(schema, List.of(
                row(2, "ITIMPCASE", "Original Name", "a@example.com"))), first.getId());

        Set<String> existing = schema.findExisting(Set.of("ITIMPCASE"));
        assertThat(existing).containsExactly("ITIMPCASE");

        ImportBatch second = batch();
        ImportPreview preview = engine.validate(schema, List.of(
                row(2, "itimpcase", "Updated Name", "a@example.com")));

        assertThat(preview.willUpdate()).isEqualTo(1);
        commit(preview, second.getId());

        assertThat(clients.findAll().stream().filter(this::isFixture)).hasSize(1);
        assertThat(clients.findByClientCode("ITIMPCASE")).get()
                .extracting(Client::getName).isEqualTo("Updated Name");
    }

    @Test
    @DisplayName("an update leaves fields the file did not carry alone")
    void anUpdateDoesNotBlankUnmappedFields() {
        // The difference between a spreadsheet that corrects a phone number and
        // one that erases every address in the account. The preview cannot tell
        // the user which they are about to get, so it has to be the safe one.
        ImportBatch first = batch();
        Map<String, String> full = new LinkedHashMap<>();
        full.put("clientCode", "ITIMPKEEP");
        full.put("name", "Keep Everything");
        full.put("city", "Mumbai");
        full.put("country", "India");
        full.put("phone", "+91 22 4000 1000");
        commit(engine.validate(schema, List.of(new ImportRow(2, full))), first.getId());

        // A second file with only code, name and phone — no city, no country.
        ImportBatch second = batch();
        Map<String, String> partial = new LinkedHashMap<>();
        partial.put("clientCode", "ITIMPKEEP");
        partial.put("name", "Keep Everything");
        partial.put("phone", "+91 22 4000 2000");
        commit(engine.validate(schema, List.of(new ImportRow(2, partial))), second.getId());

        Client saved = clients.findByClientCode("ITIMPKEEP").orElseThrow();
        assertThat(saved.getPhone()).isEqualTo("+91 22 4000 2000");
        assertThat(saved.getCity()).as("untouched by a file that did not carry it").isEqualTo("Mumbai");
        assertThat(saved.getCountry()).isEqualTo("India");
    }

    @Test
    @DisplayName("the batch id is stamped on insert and not rewritten on update")
    void batchAttributionSurvivesALaterUpdate() {
        // B-037 reverses a bad import as a set. If an update re-attributed the
        // row to the batch that last touched it, reversing that later batch
        // would delete clients it merely edited.
        ImportBatch first = batch();
        commit(engine.validate(schema, List.of(
                row(2, "ITIMPBATCH", "Original", "a@example.com"))), first.getId());

        ImportBatch second = batch();
        commit(engine.validate(schema, List.of(
                row(2, "ITIMPBATCH", "Edited", "a@example.com"))), second.getId());

        assertThat(clients.findByClientCode("ITIMPBATCH")).get()
                .extracting(Client::getImportBatchId).isEqualTo(first.getId());
        assertThat(clients.findByImportBatchId(second.getId())).isEmpty();
    }

    // ── the batched probe, against the real index ───────────────────────────

    @Test
    @DisplayName("the existence probe answers for a whole file in one query")
    void probesInBulk() {
        ImportBatch batch = batch();
        List<ImportRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            rows.add(row(i + 2, "ITIMPBULK" + i, "Bulk " + i, "bulk" + i + "@example.com"));
        }
        commit(engine.validate(schema, rows), batch.getId());

        Set<String> probe = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 50; i++) {
            probe.add("ITIMPBULK" + i);
        }
        probe.add("ITIMPNOTTHERE");

        Set<String> existing = schema.findExisting(probe);

        assertThat(existing).hasSize(50).doesNotContain("ITIMPNOTTHERE");
    }

    @Test
    @DisplayName("a dry run against the real database still writes nothing")
    void theDryRunWritesNothing() {
        long before = clients.count();

        ImportPreview preview = engine.validate(schema, List.of(
                row(2, "ITIMPDRY1", "Never Written", "dry@example.com"),
                row(3, "ITIMPDRY2", "Also Never", "dry2@example.com")));

        assertThat(preview.willCreate()).isEqualTo(2);
        assertThat(clients.count()).isEqualTo(before);
        assertThat(clients.findByClientCode("ITIMPDRY1")).isEmpty();
    }

    // ── the status vocabulary, against ck_import_batches_status ─────────────

    @Test
    @DisplayName("a batch is born QUEUED — the migration's default, not the entity's opinion")
    void batchesAreBornQueued() {
        // Inserted through JDBC, not the entity, so this is the column's own
        // default rather than the field initialiser agreeing with itself.
        jdbc.update("INSERT INTO import_batches (entity, file_name, total_rows) VALUES (?, ?, ?)",
                "CLIENT", "defaulted.xlsx", 0);

        String status = jdbc.queryForObject(
                "SELECT status FROM import_batches WHERE file_name = 'defaulted.xlsx'", String.class);

        assertThat(status).isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("the CHECK refuses the vocabulary the column used to carry")
    void theOldVocabularyIsRejectedByTheDatabase() {
        // V20260810_2010 replaced PENDING|VALIDATING|COMMITTING|DONE with the
        // contract's four. The constraint is what stops the two sets diverging
        // again, so it is worth proving it is actually on the table.
        assertThatThrownBy(() ->
                jdbc.update("INSERT INTO import_batches (entity, status, total_rows) VALUES (?, ?, ?)",
                        "CLIENT", "COMMITTING", 0))
                .hasMessageContaining("ck_import_batches_status");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private ImportBatch batch() {
        ImportBatch batch = new ImportBatch();
        batch.setEntity(schema.entityCode());
        batch.setFileName("clients.xlsx");
        batch.setStatus(ImportBatchStatus.RUNNING);
        return batches.save(batch);
    }

    /** What B-035's commit job will do: write the writable rows, nothing else. */
    private void commit(ImportPreview preview, Long batchId) {
        preview.writable().forEach(verdict ->
                schema.upsert(new ImportRow(verdict.rowNumber(), verdict.values()), batchId));
    }

    private boolean isFixture(Client client) {
        return client.getClientCode().startsWith("ITIMP");
    }

    private static ImportRow row(int rowNumber, String code, String name, String email) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("clientCode", code);
        if (name != null) {
            values.put("name", name);
        }
        values.put("primaryEmail", email);
        return new ImportRow(rowNumber, values);
    }
}
