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
import org.springframework.security.crypto.password.PasswordEncoder;
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

/**
 * B-038 · the resource registration against a real MySQL.
 *
 * <p>{@code ResourceImportSchemaTest} proves the declarations say what they
 * should; this proves they are true <em>of the table</em>, which is where they
 * can be wrong in ways no unit test can show: the {@code utf8mb4_0900_ai_ci}
 * collation deciding whether {@code edu-0142} finds {@code EDU-0142}, three
 * unique indexes rather than one, a role that is a foreign key and not a string,
 * and a {@code DECIMAL(4,2)} that reads back as {@code 8.00} against a
 * spreadsheet that said {@code 8}.
 *
 * <p><b>The scenario is the one that actually happens.</b> HR sends a joiner
 * list, one row is wrong, they fix it and re-send the whole file. The failure
 * this guards against is ending with seven accounts instead of four — silently,
 * because every row "succeeded".
 *
 * <p>Fixture codes are {@code ITRES*} so nothing here collides with another
 * suite's rows. Row counts are deliberately small: every created row costs one
 * Argon2id hash at §10.3's parameters, which is the point of the algorithm and
 * not something to run fifty of to prove a batched query works.
 */
@SpringBootTest
@Testcontainers
class ResourceImportUpsertIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_resource_import_it")
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
    ImportSchemaRegistry registry;

    @Autowired
    ImportBatchRepository batches;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clearFixtureRows() {
        // Users first — users.import_batch_id is a foreign key into the table
        // cleared on the next line, and this task's migration is what put it
        // there.
        jdbc.update("DELETE FROM users WHERE emp_code LIKE 'ITRES%'");
        jdbc.update("DELETE FROM import_batches WHERE file_name = 'resources.xlsx'");
    }

    // ── the registry, wired for real ────────────────────────────────────────

    /**
     * <b>The assertion B-038 exists to make true.</b>
     *
     * <p>{@code contracts/openapi.yaml} has constrained {@code {schema}} to
     * {@code [clients, users]} since D-001 and the generated TypeScript client
     * has been able to call {@code /imports/users/…} for as long. Until this
     * registration landed, every one of those calls answered 404.
     */
    @Test
    @DisplayName("the resource schema answers to the path the contract already declared")
    void isRegisteredUnderTheContractKey() {
        assertThat(registry.resolve("users")).isSameAs(schema);
        assertThat(schema.entityCode()).isEqualTo("RESOURCE");
        assertThat(registry.keys()).containsExactlyInAnyOrder("clients", "users");
    }

    // ── the rule the feature is judged on ───────────────────────────────────

    @Test
    @DisplayName("re-sending a corrected joiner list updates and never duplicates")
    void reUploadingACorrectedFileDoesNotDuplicate() {
        ImportBatch first = batch();

        // Run 1 — one row is rejected for a missing email.
        List<ImportRow> original = List.of(
                joiner(2, "ITRESA", "Asha Menon", "asha.menon", "asha@example.test"),
                joiner(3, "ITRESB", "Bhavin Rao", "bhavin.rao", "bhavin@example.test"),
                joiner(4, "ITRESC", "Chitra Iyer", "chitra.iyer", null),
                joiner(5, "ITRESD", "Deepak Shah", "deepak.shah", "deepak@example.test"));

        ImportPreview preview = engine.validate(schema, original);
        assertThat(preview.willCreate()).isEqualTo(3);
        assertThat(preview.rejected()).isEqualTo(1);
        commit(preview, first.getId());

        assertThat(fixtureCount()).isEqualTo(3);

        // Run 2 — the corrected file, re-sent whole, which is what people do.
        ImportBatch second = batch();
        List<ImportRow> corrected = List.of(
                joiner(2, "ITRESA", "Asha Menon", "asha.menon", "asha@example.test"),
                joiner(3, "ITRESB", "Bhavin Rao", "bhavin.rao", "bhavin@example.test"),
                joiner(4, "ITRESC", "Chitra Iyer", "chitra.iyer", "chitra@example.test"),
                joiner(5, "ITRESD", "Deepak Shah", "deepak.shah", "deepak@example.test"));

        ImportPreview rerun = engine.validate(schema, corrected);
        assertThat(rerun.willUpdate()).isEqualTo(3);
        assertThat(rerun.willCreate()).isEqualTo(1);
        assertThat(rerun.rejected()).isZero();
        commit(rerun, second.getId());

        assertThat(fixtureCount()).as("four resources, not seven").isEqualTo(4);
        assertThat(fullNameOf("ITRESC")).isEqualTo("Chitra Iyer");
    }

    /**
     * {@code uq_users_emp_code} collates {@code utf8mb4_0900_ai_ci}, so the index
     * treats these as one row. If the engine disagreed with the index the dry run
     * would promise a create and the commit would hit a duplicate key — the worst
     * outcome, halfway through a batch.
     */
    @Test
    @DisplayName("a differently-cased employee code finds the existing person, not a second one")
    void matchesAcrossCase() {
        commit(engine.validate(schema, List.of(
                joiner(2, "ITRESCASE", "Original Name", "orig.name", "orig@example.test"))),
                batch().getId());

        assertThat(schema.findExisting(Set.of("ITRESCASE"))).containsOnlyKeys("ITRESCASE");

        ImportPreview preview = engine.validate(schema, List.of(
                joiner(2, "itrescase", "Renamed Person", "orig.name", "orig@example.test")));
        assertThat(preview.willUpdate()).isEqualTo(1);
        commit(preview, batch().getId());

        assertThat(fixtureCount()).isEqualTo(1);
        assertThat(fullNameOf("ITRESCASE")).isEqualTo("Renamed Person");
    }

    /**
     * The difference between a spreadsheet that corrects two mobile numbers and
     * one that erases every department in the organisation. The preview cannot
     * tell the user which they are about to get, so it has to be the safe one.
     */
    @Test
    @DisplayName("an update leaves columns the file did not carry alone")
    void anUpdateDoesNotBlankUnmappedFields() {
        Map<String, String> full = new LinkedHashMap<>(base("ITRESKEEP", "Keep Everything",
                "keep.everything", "keep@example.test"));
        full.put("department", "Engineering");
        full.put("location", "Pune");
        full.put("mobile", "+91 98200 11223");
        commit(engine.validate(schema, List.of(new ImportRow(2, full))), batch().getId());

        // A second file with only the required columns and a new mobile.
        Map<String, String> partial = new LinkedHashMap<>(base("ITRESKEEP", "Keep Everything",
                "keep.everything", "keep@example.test"));
        partial.put("mobile", "+91 98200 44556");
        commit(engine.validate(schema, List.of(new ImportRow(3, partial))), batch().getId());

        Map<String, Object> saved = rowOf("ITRESKEEP");
        assertThat(saved.get("mobile")).isEqualTo("+91 98200 44556");
        assertThat(saved.get("department"))
                .as("untouched by a file that did not carry the column").isEqualTo("Engineering");
        assertThat(saved.get("location")).isEqualTo("Pune");
    }

    // ── what only this entity can get wrong ─────────────────────────────────

    /**
     * The role is a foreign key, and the spreadsheet holds a code.
     *
     * <p>The unit test proves the declared vocabulary matches the form's; this
     * proves the codes it declares are ones {@code roles.code} actually carries.
     * A vocabulary that is internally consistent and matches no seeded row would
     * pass every check up to the commit and then fail every single write.
     */
    @Test
    @DisplayName("every declared role code resolves against the seeded roles table")
    void everyDeclaredRoleResolves() {
        List<String> declared = schema.fields().stream()
                .filter(f -> f.name().equals("role"))
                .findFirst().orElseThrow()
                .allowedValues();

        List<String> seeded = jdbc.queryForList(
                "SELECT code FROM roles WHERE code IN ("
                        + declared.stream().map(c -> "?").reduce((a, b) -> a + ", " + b).orElse("''")
                        + ")", String.class, declared.toArray());

        assertThat(seeded).containsExactlyInAnyOrderElementsOf(declared);
    }

    /**
     * <b>An imported account has a password nobody knows and must change it.</b>
     *
     * <p>Not asserted as "the column is non-empty": the failure worth catching is
     * a placeholder that some known string matches, and only the encoder can say.
     * The generated password is never returned anywhere, so the way in is
     * {@code POST /auth/forgot-password} — see the registration's class comment.
     */
    @Test
    @DisplayName("a created account is unauthenticable and flagged to change its password")
    void createdAccountsCarryAnUnknownCredential() {
        commit(engine.validate(schema, List.of(
                joiner(2, "ITRESPWD", "Password Fixture", "pwd.fixture", "pwd@example.test"))),
                batch().getId());

        Map<String, Object> saved = rowOf("ITRESPWD");
        String hash = (String) saved.get("password_hash");

        assertThat(hash).isNotBlank();
        assertThat(passwordEncoder.matches("password", hash)).isFalse();
        assertThat(passwordEncoder.matches("", hash)).isFalse();
        assertThat(passwordEncoder.matches("ITRESPWD", hash)).isFalse();
        assertThat(saved.get("must_change_password")).isEqualTo(true);
    }

    /**
     * Two people created by one run get two different hashes.
     *
     * <p>The cheap version of this feature hashes one password for the whole
     * batch, which puts a shared credential in the identity table. This is the
     * assertion that would fail if somebody optimised it that way later — the
     * registration's class comment explains why the saving was refused.
     */
    @Test
    @DisplayName("two imported accounts do not share one credential")
    void everyCreatedAccountGetsItsOwnHash() {
        commit(engine.validate(schema, List.of(
                joiner(2, "ITRESH1", "Hash One", "hash.one", "hash1@example.test"),
                joiner(3, "ITRESH2", "Hash Two", "hash.two", "hash2@example.test"))),
                batch().getId());

        assertThat(rowOf("ITRESH1").get("password_hash"))
                .isNotEqualTo(rowOf("ITRESH2").get("password_hash"));
    }

    /**
     * B-037 reverses a bad import as a set. If an update re-attributed the row to
     * the batch that last touched it, reversing that later batch would delete
     * people it merely edited.
     */
    @Test
    @DisplayName("the batch id is stamped on insert and not rewritten on update")
    void batchAttributionSurvivesALaterUpdate() {
        ImportBatch first = batch();
        commit(engine.validate(schema, List.of(
                joiner(2, "ITRESBATCH", "Original", "orig.batch", "batch@example.test"))),
                first.getId());

        ImportBatch second = batch();
        commit(engine.validate(schema, List.of(
                joiner(2, "ITRESBATCH", "Edited", "orig.batch", "batch@example.test"))),
                second.getId());

        assertThat(rowOf("ITRESBATCH").get("import_batch_id")).isEqualTo(first.getId());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE import_batch_id = ?", Integer.class,
                second.getId())).isZero();
    }

    // ── the preview's message, against the real columns ─────────────────────

    /**
     * The changed-field message, end to end — blueprint §4B.3's
     * {@code ♻ Will update │ Name, phone}.
     *
     * <p>Here rather than only in the engine's unit tests because the half that
     * can silently be wrong is the projection: a column read from the wrong
     * alias, or formatted differently from the way the import reads it, produces
     * a message that is confidently incorrect. A stub schema cannot catch that —
     * it agrees with itself by construction.
     */
    @Test
    @DisplayName("an update names the fields it would change, and nothing else")
    void anUpdateNamesTheFieldsItWouldChange() {
        commit(engine.validate(schema, List.of(
                joiner(2, "ITRESDIFF", "Original Name", "diff.person", "diff@example.test"))),
                batch().getId());

        ImportPreview preview = engine.validate(schema, List.of(
                // The name differs; username and email hold the values already
                // stored, so they must not be listed. The natural key is
                // excluded by rule.
                joiner(2, "ITRESDIFF", "Renamed Person", "diff.person", "diff@example.test")));

        assertThat(preview.rows()).singleElement().satisfies(verdict -> {
            assertThat(verdict.verdict()).isEqualTo(ImportVerdict.WILL_UPDATE);
            assertThat(verdict.reason()).isEqualTo("Full Name");
        });
    }

    /**
     * <b>The trailing-zeros case, which is the one this projection would get
     * wrong by default.</b>
     *
     * <p>{@code daily_capacity_hrs} is {@code DECIMAL(4,2)} and reads back as
     * {@code 8.00}; a spreadsheet holding a capacity of eight says {@code 8}.
     * Compared as they come out of the two sides, every row of a file that
     * changed nothing would be reported as changing its capacity — a preview
     * that cries wolf on the whole organisation.
     */
    @Test
    @DisplayName("a capacity of 8 against a stored 8.00 is not a change")
    void decimalsCompareAsTheSpreadsheetWritesThem() {
        Map<String, String> withCapacity = new LinkedHashMap<>(base("ITRESCAP", "Capacity Fixture",
                "cap.fixture", "cap@example.test"));
        withCapacity.put("dailyCapacityHrs", "8");
        commit(engine.validate(schema, List.of(new ImportRow(2, withCapacity))), batch().getId());

        assertThat(schema.findExisting(Set.of("ITRESCAP")).get("ITRESCAP"))
                .containsEntry("dailyCapacityHrs", "8");

        ImportPreview preview = engine.validate(schema, List.of(new ImportRow(3, withCapacity)));
        assertThat(preview.rows()).singleElement()
                .extracting(ImportRowVerdict::reason).isEqualTo("No change");
    }

    /**
     * The JSON columns survive the round trip in the shape the spreadsheet wrote
     * them, and land in the shape {@code ck_users_weekly_off} demands.
     */
    @Test
    @DisplayName("weekly off and skills store as JSON and read back as the file's own list")
    void jsonColumnsRoundTrip() {
        Map<String, String> withLists = new LinkedHashMap<>(base("ITRESJSON", "JSON Fixture",
                "json.fixture", "json@example.test"));
        withLists.put("weeklyOff", "7, 6");
        withLists.put("skills", "Java, React, Java");
        commit(engine.validate(schema, List.of(new ImportRow(2, withLists))), batch().getId());

        Map<String, Object> saved = rowOf("ITRESJSON");
        // Sorted and de-duplicated on the way in, for the reason
        // ResourceWriteService sorts them: [7, 6] and [6, 7] are the same working
        // week, and storing them differently makes an unchanged row look edited.
        assertThat(saved.get("weekly_off").toString().replace(" ", "")).isEqualTo("[6,7]");
        assertThat(saved.get("skills").toString().replace(" ", "")).isEqualTo("[\"Java\",\"React\"]");

        assertThat(schema.findExisting(Set.of("ITRESJSON")).get("ITRESJSON"))
                .containsEntry("weeklyOff", "6, 7")
                .containsEntry("skills", "Java, React");
    }

    /** Blueprint §4B.3 makes step 4's guarantee absolute: "nothing is written yet". */
    @Test
    @DisplayName("a dry run against the real database still writes nothing")
    void theDryRunWritesNothing() {
        int before = fixtureCount();

        ImportPreview preview = engine.validate(schema, List.of(
                joiner(2, "ITRESDRY1", "Never Written", "never.written", "dry1@example.test"),
                joiner(3, "ITRESDRY2", "Also Never", "also.never", "dry2@example.test")));

        assertThat(preview.willCreate()).isEqualTo(2);
        assertThat(fixtureCount()).isEqualTo(before);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private ImportBatch batch() {
        ImportBatch batch = new ImportBatch();
        batch.setEntity(schema.entityCode());
        batch.setFileName("resources.xlsx");
        batch.setStatus(ImportBatchStatus.RUNNING);
        return batches.save(batch);
    }

    /** What B-035's commit job will do: write the writable rows, nothing else. */
    private void commit(ImportPreview preview, Long batchId) {
        preview.writable().forEach(verdict ->
                schema.upsert(new ImportRow(verdict.rowNumber(), verdict.values()), batchId));
    }

    private static Map<String, String> base(String empCode, String fullName,
                                            String username, String email) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("employeeCode", empCode);
        values.put("fullName", fullName);
        values.put("username", username);
        if (email != null) {
            values.put("email", email);
        }
        values.put("role", "DEVELOPER");
        return values;
    }

    private static ImportRow joiner(int rowNumber, String empCode, String fullName,
                                    String username, String email) {
        return new ImportRow(rowNumber, base(empCode, fullName, username, email));
    }

    private int fixtureCount() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE emp_code LIKE 'ITRES%'", Integer.class);
        return count == null ? 0 : count;
    }

    private Map<String, Object> rowOf(String empCode) {
        return jdbc.queryForMap("SELECT * FROM users WHERE emp_code = ?", empCode);
    }

    private String fullNameOf(String empCode) {
        return jdbc.queryForObject(
                "SELECT full_name FROM users WHERE emp_code = ?", String.class, empCode);
    }
}
