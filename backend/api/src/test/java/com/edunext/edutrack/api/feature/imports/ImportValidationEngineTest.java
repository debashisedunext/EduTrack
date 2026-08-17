package com.edunext.edutrack.api.feature.imports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-030 · the dry run's verdict matrix.
 *
 * <p>The first test is blueprint §4B.3's own worked example, row for row. If
 * only one test in this file survives, it should be that one: it is the
 * behaviour the product spec draws a picture of, and the picture is what the
 * screen is built from.
 */
class ImportValidationEngineTest {

    private final ImportValidationEngine engine = new ImportValidationEngine();

    // ── the blueprint's example ─────────────────────────────────────────────

    @Test
    @DisplayName("blueprint §4B.3 step 4, row for row")
    void reproducesTheBlueprintsWorkedExample() {
        TestImportSchema schema = new TestImportSchema("NORTHWIND");

        ImportPreview preview = engine.validate(schema, List.of(
                row(2, "code", "ACME", "name", "Acme Corporation"),
                row(3, "code", "NORTHWIND", "name", "Northwind Traders"),
                row(4, "code", "ACME", "name", "Acme Again"),
                row(5, "name", "No Code Here"),
                row(6, "code", "ZENITH", "name", "Zenith", "email", "not-an-email")));

        assertThat(preview.rows()).extracting(
                        ImportRowVerdict::rowNumber, ImportRowVerdict::verdict, ImportRowVerdict::reason)
                .containsExactly(
                        tuple3(2, ImportVerdict.WILL_CREATE, null),
                        tuple3(3, ImportVerdict.WILL_UPDATE, null),
                        tuple3(4, ImportVerdict.DUPLICATE_IN_FILE, "Row 2 wins"),
                        tuple3(5, ImportVerdict.REJECTED, "Code required"),
                        tuple3(6, ImportVerdict.REJECTED, "Email: Invalid email"));

        assertThat(preview.willCreate()).isEqualTo(1);
        assertThat(preview.willUpdate()).isEqualTo(1);
        assertThat(preview.duplicates()).isEqualTo(1);
        assertThat(preview.rejected()).isEqualTo(2);
    }

    // ── the guarantee the step exists for ───────────────────────────────────

    @Test
    @DisplayName("a dry run never calls upsert — TestImportSchema throws if it does")
    void writesNothing() {
        TestImportSchema schema = new TestImportSchema("NORTHWIND");

        // Every verdict is represented, so no branch reaches a write.
        engine.validate(schema, List.of(
                row(2, "code", "NEW", "name", "Creates"),
                row(3, "code", "NORTHWIND", "name", "Updates"),
                row(4, "code", "NEW", "name", "Duplicates"),
                row(5, "name", "Rejected")));
    }

    @Nested
    class DuplicateDetection {

        @Test
        @DisplayName("the first occurrence wins and the reason names it")
        void firstRowWins() {
            ImportPreview preview = new ImportValidationEngine().validate(
                    new TestImportSchema(), List.of(
                            row(7, "code", "ACME", "name", "First"),
                            row(8, "code", "ACME", "name", "Second"),
                            row(9, "code", "ACME", "name", "Third")));

            assertThat(preview.rows()).extracting(ImportRowVerdict::verdict).containsExactly(
                    ImportVerdict.WILL_CREATE,
                    ImportVerdict.DUPLICATE_IN_FILE,
                    ImportVerdict.DUPLICATE_IN_FILE);
            // Both later rows point at row 7 — not at each other, and not at
            // "the previous duplicate", which would send the user to a row that
            // is itself skipped.
            assertThat(preview.rows().get(1).reason()).isEqualTo("Row 7 wins");
            assertThat(preview.rows().get(2).reason()).isEqualTo("Row 7 wins");
        }

        @Test
        @DisplayName("case and surrounding space do not make a second client")
        void normalisesBeforeComparing() {
            ImportPreview preview = new ImportValidationEngine().validate(
                    new TestImportSchema(), List.of(
                            row(2, "code", "ACME", "name", "First"),
                            row(3, "code", "acme", "name", "Second"),
                            row(4, "code", "  Acme  ", "name", "Third")));

            assertThat(preview.willCreate()).isEqualTo(1);
            assertThat(preview.duplicates()).isEqualTo(2);
        }

        @Test
        @DisplayName("a rejected row does not claim its key — the next valid row still creates")
        void rejectedRowsDoNotBlockTheKey() {
            // Row 2 is rejected for a bad code shape. If it still reserved
            // "AC-ME", row 3 would be reported as a duplicate of a row that is
            // never written, and the client would silently not be imported.
            ImportPreview preview = new ImportValidationEngine().validate(
                    new TestImportSchema(), List.of(
                            row(2, "code", "AC-ME", "name", "Punctuated"),
                            row(3, "code", "ACME", "name", "Fine")));

            assertThat(preview.rows()).extracting(ImportRowVerdict::verdict)
                    .containsExactly(ImportVerdict.REJECTED, ImportVerdict.WILL_CREATE);
        }
    }

    @Nested
    class ExistenceProbe {

        @Test
        @DisplayName("one query for the whole file, not one per row")
        void probesOnce() {
            TestImportSchema schema = new TestImportSchema("A2", "A5");
            List<ImportRow> rows = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                rows.add(row(i + 2, "code", "A" + i, "name", "Row " + i));
            }

            new ImportValidationEngine().validate(schema, rows);

            assertThat(schema.probes).hasSize(1);
            assertThat(schema.probes.getFirst()).hasSize(500);
        }

        @Test
        @DisplayName("no probe at all when every row was rejected")
        void skipsTheProbeWhenNothingSurvives() {
            TestImportSchema schema = new TestImportSchema();

            ImportPreview preview = new ImportValidationEngine().validate(schema, List.of(
                    row(2, "name", "No code"),
                    row(3, "name", "Also no code")));

            assertThat(schema.probes).isEmpty();
            assertThat(preview.rejected()).isEqualTo(2);
        }

        @Test
        @DisplayName("rejected and duplicate rows are not probed")
        void probesOnlyWhatCouldBeWritten() {
            TestImportSchema schema = new TestImportSchema();

            new ImportValidationEngine().validate(schema, List.of(
                    row(2, "code", "ACME", "name", "First"),
                    row(3, "code", "ACME", "name", "Duplicate"),
                    row(4, "name", "Rejected")));

            assertThat(schema.probes.getFirst()).containsExactly("ACME");
        }
    }

    @Nested
    class Rejection {

        @Test
        @DisplayName("one reason per row, the leftmost problem in template order")
        void reportsTheFirstProblemOnly() {
            // Both the code and the name are missing. The user is told about
            // the code, because it is the first column of the template and
            // they will re-upload after fixing whatever they are told about.
            ImportPreview preview = new ImportValidationEngine().validate(
                    new TestImportSchema(), List.of(row(2, "email", "someone@example.com")));

            assertThat(preview.rows().getFirst().reason()).isEqualTo("Code required");
        }

        @Test
        @DisplayName("reasons name the column by its header, not by our field name")
        void namesTheColumnTheUserSees() {
            ImportPreview preview = new ImportValidationEngine().validate(
                    new TestImportSchema(), List.of(
                            row(2, "code", "ACME", "name", "Acme", "notes", "far too long to fit")));

            assertThat(preview.rows().getFirst().reason())
                    .isEqualTo("Notes: Longer than 10 characters");
        }

        @Test
        @DisplayName("an ENUM value outside the dropdown is rejected, case-insensitively accepted inside it")
        void checksTheEnumDomain() {
            ImportPreview preview = new ImportValidationEngine().validate(
                    new TestImportSchema(), List.of(
                            row(2, "code", "A", "name", "Lower", "status", "active"),
                            row(3, "code", "B", "name", "Wrong", "status", "PENDING")));

            assertThat(preview.rows().getFirst().verdict()).isEqualTo(ImportVerdict.WILL_CREATE);
            assertThat(preview.rows().get(1).verdict()).isEqualTo(ImportVerdict.REJECTED);
            assertThat(preview.rows().get(1).reason())
                    .isEqualTo("Status: Must be one of: ACTIVE, INACTIVE");
        }

        @Test
        @DisplayName("an absent optional field is not run through its validators")
        void skipsValidatorsForAbsentOptionals() {
            // The email validator would reject "", so a blank optional cell
            // reaching it would reject a row that is perfectly fine.
            ImportPreview preview = new ImportValidationEngine().validate(
                    new TestImportSchema(), List.of(row(2, "code", "ACME", "name", "Acme")));

            assertThat(preview.rows().getFirst().verdict()).isEqualTo(ImportVerdict.WILL_CREATE);
        }
    }

    /**
     * B-034 · blueprint §4B.3's Message column for an update — {@code Name,
     * phone}.
     *
     * <p>The rest of this file is about which of four verdicts a row gets. This
     * nested class is about the sentence next to one of them, and it earns its
     * place because "will update" without it is a verdict a user cannot act on:
     * a file correcting six phone numbers and a file overwriting every address
     * in the account are the same word.
     */
    @Nested
    class ChangedFields {

        @Test
        @DisplayName("an update names the fields that differ, in template order")
        void namesTheChangedFields() {
            TestImportSchema schema = new TestImportSchema()
                    .holding("NORTHWIND", Map.of(
                            "name", "Northwind Traders",
                            "email", "old@example.com",
                            "status", "ACTIVE"));

            ImportPreview preview = engine.validate(schema, List.of(
                    row(2, "code", "NORTHWIND",
                            "name", "Northwind Trading Ltd",
                            "email", "new@example.com",
                            "status", "ACTIVE")));

            // Headers, not field names — the user is reading this against their
            // own spreadsheet. Template order, not the order the row happened to
            // carry, so the same change reads the same way twice running.
            assertThat(preview.rows()).singleElement()
                    .extracting(ImportRowVerdict::reason).isEqualTo("Name, Email");
        }

        @Test
        @DisplayName("a field the row does not carry is not a change — the commit leaves it alone")
        void unmappedAndBlankFieldsAreNotChanges() {
            // The rule ClientImportSchema#upsert enforces: only fields present
            // in the row are written. Reporting the absent ones would promise an
            // erasure the import will not perform, which is the single most
            // alarming thing this message could get wrong.
            TestImportSchema schema = new TestImportSchema()
                    .holding("ACME", Map.of("name", "Acme", "email", "a@example.com"));

            ImportPreview preview = engine.validate(schema, List.of(
                    row(2, "code", "ACME", "name", "Acme Corporation")));

            assertThat(preview.rows()).singleElement()
                    .extracting(ImportRowVerdict::reason).isEqualTo("Name");
        }

        @Test
        @DisplayName("the natural key is never listed, even when the case differs")
        void theNaturalKeyIsNeverAChange() {
            // The collation matched `acme` to `ACME` and the upsert leaves the
            // stored spelling alone, so listing it would name a change that does
            // not happen.
            TestImportSchema schema = new TestImportSchema()
                    .holding("ACME", Map.of("code", "ACME", "name", "Acme"));

            ImportPreview preview = engine.validate(schema, List.of(
                    row(2, "code", "acme", "name", "Acme")));

            assertThat(preview.rows()).singleElement()
                    .extracting(ImportRowVerdict::verdict, ImportRowVerdict::reason)
                    .containsExactly(ImportVerdict.WILL_UPDATE, "No change");
        }

        @Test
        @DisplayName("a value against a field that is currently empty is a change")
        void fillingAnEmptyFieldIsAChange() {
            TestImportSchema schema = new TestImportSchema()
                    .holding("ACME", Map.of("name", "Acme"));

            ImportPreview preview = engine.validate(schema, List.of(
                    row(2, "code", "ACME", "name", "Acme", "email", "new@example.com")));

            assertThat(preview.rows()).singleElement()
                    .extracting(ImportRowVerdict::reason).isEqualTo("Email");
        }

        @Test
        @DisplayName("a registration that supplies no values gets no message, not 'No change'")
        void noValuesMeansNoClaim() {
            // The empty map the SPI permits. "No change" would be a claim
            // nothing checked, and the row is still an update either way.
            ImportPreview preview = engine.validate(new TestImportSchema("ACME"), List.of(
                    row(2, "code", "ACME", "name", "Anything At All")));

            assertThat(preview.rows()).singleElement()
                    .extracting(ImportRowVerdict::verdict, ImportRowVerdict::reason)
                    .containsExactly(ImportVerdict.WILL_UPDATE, null);
        }
    }

    @Test
    @DisplayName("output keeps the file's row order, so it reads against the spreadsheet")
    void preservesRowOrder() {
        ImportPreview preview = new ImportValidationEngine().validate(
                new TestImportSchema("B"), List.of(
                        row(2, "code", "A", "name", "Creates"),
                        row(3, "name", "Rejected"),
                        row(4, "code", "B", "name", "Updates"),
                        row(5, "code", "A", "name", "Duplicate")));

        assertThat(preview.rows()).extracting(ImportRowVerdict::rowNumber)
                .containsExactly(2, 3, 4, 5);
    }

    @Test
    void writableIsCreatesAndUpdatesOnly() {
        ImportPreview preview = new ImportValidationEngine().validate(
                new TestImportSchema("B"), List.of(
                        row(2, "code", "A", "name", "Creates"),
                        row(3, "code", "B", "name", "Updates"),
                        row(4, "code", "A", "name", "Duplicate"),
                        row(5, "name", "Rejected")));

        assertThat(preview.writable()).extracting(ImportRowVerdict::rowNumber)
                .containsExactly(2, 3);
    }

    @Test
    void anEmptyFileIsAnEmptyPreview() {
        ImportPreview preview = new ImportValidationEngine().validate(
                new TestImportSchema(), List.of());

        assertThat(preview.rows()).isEmpty();
        assertThat(preview.willCreate()).isZero();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static ImportRow row(int rowNumber, String... keysAndValues) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return new ImportRow(rowNumber, values);
    }

    private static org.assertj.core.groups.Tuple tuple3(int row, ImportVerdict verdict, String reason) {
        return org.assertj.core.groups.Tuple.tuple(row, verdict, reason);
    }
}
