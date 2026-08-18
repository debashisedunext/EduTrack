package com.edunext.edutrack.api.feature.imports;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-031 · the template, read back out of the bytes it was written into.
 *
 * <p>Every assertion here goes through POI's reader rather than through the
 * writer's own fields. A test that asked {@code ImportTemplateWriter} what it
 * had written would pass on a workbook Excel refuses to open — and "the file
 * downloads and then will not open" is the one failure this feature cannot
 * report to itself.
 *
 * <p><b>Written against {@link TestImportSchema} and the two schemas below, not
 * against the client master.</b> Same argument the engine tests make: asserting
 * the template against {@code ClientImportSchema} would make adding a column to
 * the client master break tests about freeze panes, and it would stop proving
 * the thing this writer is for — that it can render a schema it has never heard
 * of. The client-specific promises of blueprint §4B.3 — dropdowns on Status and
 * Support Plan, one filled example row — are asserted against the real
 * registration in {@code ImportTemplateControllerTest}.
 */
class ImportTemplateWriterTest {

    private final ImportTemplateWriter writer = new ImportTemplateWriter();

    /**
     * A schema with an example on every column, so the example row can be
     * asserted as a whole rather than one populated cell at a time.
     */
    private static final ImportSchemaDefinition EXAMPLED = new StubSchema("gadgets", "GADGET",
            List.of(ImportField.required("code", "Gadget Code").maxLength(10).example("GAD-1"),
                    ImportField.required("name", "Name").maxLength(40).example("Widget Press"),
                    ImportField.optional("tier", "Tier").oneOf("BASIC", "PREMIUM").example("BASIC"),
                    ImportField.optional("since", "In Service Since")
                            .type(ImportFieldType.DATE).example("2026-04-01")));

    // ── the header row ──────────────────────────────────────────────────────

    @Test
    @DisplayName("the header row is exactly the schema's headers, in declaration order")
    void headerRowIsTheSchemasHeaders() throws IOException {
        Row header = sheet(new TestImportSchema()).getRow(0);

        assertThat(cells(header))
                .containsExactly("Code", "Name", "Email", "Status", "Notes");
    }

    /**
     * <b>The assertion this whole file exists for.</b>
     *
     * <p>The template's headers are undecorated — no asterisk on the required
     * ones, no "(required)", no type hint — and the reason is not taste.
     * {@link HeaderMatcher} matches an uploaded file's headings against
     * {@code ImportField#header()}, so anything appended here would make the
     * file this product handed the user fail to auto-match when they upload it
     * back at step 2, and B-033 would present twenty columns for manual mapping.
     *
     * <p>Stated as a round trip rather than as "the strings are equal", because
     * the round trip is the property that matters and it keeps holding if
     * somebody makes the matcher smarter.
     */
    @Test
    @DisplayName("the template a user downloads auto-maps completely when they upload it back")
    void theTemplateRoundTripsThroughTheHeaderMatcher() throws IOException {
        ImportSchemaDefinition schema = new TestImportSchema();

        List<String> headers = cells(sheet(schema).getRow(0));
        ImportMapping mapping = HeaderMatcher.suggest(schema.fields(), headers);

        assertThat(mapping.missingRequired(schema.fields()))
                .as("a required column of our own template did not auto-match")
                .isEmpty();
        assertThat(mapping.targetToSource().keySet())
                .as("every column should auto-match, not only the required ones")
                .containsExactlyInAnyOrderElementsOf(
                        schema.fields().stream().map(ImportField::name).toList());
    }

    @Test
    @DisplayName("the header row is frozen, and the example row is not")
    void headerRowIsFrozen() throws IOException {
        Sheet sheet = sheet(new TestImportSchema());

        // Row 1 in POI's pane is "the first scrolling row is index 1", i.e. only
        // row 0 is pinned. Pinning row 1 as well would keep an example the
        // instructions tell them to delete on screen for the whole of the job.
        assertThat(sheet.getPaneInformation()).isNotNull();
        assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 1);
    }

    // ── the example row ─────────────────────────────────────────────────────

    @Test
    @DisplayName("row 2 is the schema's worked example, cell for cell")
    void exampleRowIsWrittenFromTheFields() throws IOException {
        Row example = sheet(EXAMPLED).getRow(1);

        assertThat(cells(example))
                .containsExactly("GAD-1", "Widget Press", "BASIC", "2026-04-01");
    }

    /**
     * A field with no {@code example()} leaves a blank cell rather than the
     * string {@code "null"} — which is what {@code setCellValue} would write if
     * the null check went missing, and which the dry run would then reject as an
     * over-long or malformed value the user never typed.
     */
    @Test
    @DisplayName("a column with no declared example is left blank, not filled with 'null'")
    void columnsWithoutAnExampleAreBlank() throws IOException {
        Row example = sheet(new TestImportSchema()).getRow(1);

        assertThat(cells(example)).containsOnly("");
    }

    /**
     * Every cell is text-formatted, so Excel does not helpfully turn
     * {@code 00123} into 123 or a phone number into a formula. The rejection
     * that follows names a value the user never typed, in a file they can see
     * nothing wrong with.
     */
    @Test
    @DisplayName("cells are text-formatted, so leading zeroes survive")
    void cellsAreTextFormatted() throws IOException {
        Sheet sheet = sheet(EXAMPLED);
        Cell code = sheet.getRow(1).getCell(0);

        assertThat(code.getCellType()).isEqualTo(CellType.STRING);
        assertThat(code.getCellStyle().getDataFormatString()).isEqualTo("@");
    }

    // ── the dropdowns ───────────────────────────────────────────────────────

    /**
     * The dropdown's values are {@code allowedValues} itself, which is also what
     * {@link ImportValidationEngine} checks an uploaded cell against — so the
     * template cannot offer a value the import rejects. Held as two lists they
     * drift the first time somebody adds a tier.
     */
    @Test
    @DisplayName("every ENUM column carries a dropdown of exactly its allowed values")
    void enumColumnsGetADropdown() throws IOException {
        List<? extends DataValidation> validations = sheet(EXAMPLED).getDataValidations();

        assertThat(validations).hasSize(1);
        DataValidationConstraint constraint = validations.getFirst().getValidationConstraint();
        assertThat(constraint.getExplicitListValues()).containsExactly("BASIC", "PREMIUM");
    }

    @Test
    @DisplayName("the dropdown covers the third column and the 5,000 rows the upload accepts")
    void dropdownCoversTheImportableRange() throws IOException {
        DataValidation validation = sheet(EXAMPLED).getDataValidations().getFirst();

        var range = validation.getRegions().getCellRangeAddresses()[0];
        assertThat(range.getFirstColumn()).isEqualTo(2);
        assertThat(range.getLastColumn()).isEqualTo(2);
        // From the example row to the last row B-032's 5,000-row cap will accept.
        assertThat(range.getFirstRow()).isEqualTo(1);
        assertThat(range.getLastRow()).isEqualTo(5_000);
    }

    /**
     * The list is a constraint, not a suggestion. Without the error box Excel
     * offers the values and accepts anything typed past them, which is the worst
     * of both: the user believes the cell was checked and step 4 rejects it.
     */
    @Test
    @DisplayName("a value typed past the dropdown is refused by Excel, not merely discouraged")
    void theDropdownShowsAnErrorBox() throws IOException {
        DataValidation validation = sheet(EXAMPLED).getDataValidations().getFirst();

        assertThat(validation.getShowErrorBox()).isTrue();
        assertThat(validation.getSuppressDropDownArrow()).isFalse();
    }

    /**
     * Excel's explicit-list formula stops at 255 characters, and a reader that
     * hits the limit either drops the constraint or refuses the file. Writing
     * the sheet without that column's dropdown is the tempting fallback and is
     * the worse outcome: the column would look like free text, the user would
     * type their own spelling, and the refusal would arrive at step 4 pointing
     * at a cell the template never constrained.
     */
    @Test
    @DisplayName("a schema whose dropdown Excel cannot carry fails loudly, not silently")
    void anOversizedDropdownIsRefused() {
        List<String> tooMany = IntStream.range(0, 40)
                .mapToObj(i -> "VALUE-%02d".formatted(i))
                .toList();
        ImportSchemaDefinition oversized = new StubSchema("oversized", "OVERSIZED",
                List.of(ImportField.required("code", "Code").example("X"),
                        ImportField.optional("big", "Big").oneOf(tooMany.toArray(String[]::new))));

        assertThatThrownBy(() -> writer.write(oversized, new ByteArrayOutputStream()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'big'")
                .hasMessageContaining("hidden lookup sheet");
    }

    // ── the instructions ────────────────────────────────────────────────────

    @Test
    @DisplayName("the data sheet is first, so step 2's default lands on the right one")
    void dataSheetIsFirst() throws IOException {
        try (XSSFWorkbook workbook = written(new TestImportSchema())) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("Widgets");
            assertThat(workbook.getSheetAt(1).getSheetName()).isEqualTo("Instructions");
        }
    }

    /**
     * Required-ness has to be somewhere, and it cannot be in the header row.
     * This is where it went, along with the two rules a user cannot guess: that
     * the natural key silently updates rather than duplicating, and that a blank
     * cell does not clear a stored value.
     */
    @Test
    @DisplayName("the instructions name the natural key, the example row and the required columns")
    void instructionsCarryWhatTheHeaderRowCannot() throws IOException {
        try (XSSFWorkbook workbook = written(new TestImportSchema())) {
            String text = textOf(workbook.getSheet("Instructions"));

            assertThat(text).contains("Code");
            assertThat(text).contains("Required");
            assertThat(text).contains("Optional");
            assertThat(text).contains("Row 2 is a worked example");
            assertThat(text).contains("it never creates a second one");
            assertThat(text).contains("ACTIVE, INACTIVE");
        }
    }

    // ── naming ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the download is named after the schema and is not dated")
    void filenameIsStable() {
        assertThat(ImportTemplateWriter.filename(new TestImportSchema()))
                .isEqualTo("widgets-import-template.xlsx");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Sheet sheet(ImportSchemaDefinition schema) throws IOException {
        return written(schema).getSheetAt(0);
    }

    /**
     * Reads the bytes back with the DOM reader — deliberately, and only here.
     * The production path streams (SXSSF); a test is one small workbook and
     * wants random access to it.
     */
    private XSSFWorkbook written(ImportSchemaDefinition schema) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writer.write(schema, bytes);
        return new XSSFWorkbook(new ByteArrayInputStream(bytes.toByteArray()));
    }

    private static List<String> cells(Row row) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < row.getLastCellNum(); i++) {
            Cell cell = row.getCell(i);
            values.add(cell == null ? "" : cell.getStringCellValue());
        }
        return values;
    }

    private static String textOf(Sheet sheet) {
        StringBuilder text = new StringBuilder();
        for (Row row : sheet) {
            for (Cell cell : row) {
                text.append(cell.getStringCellValue()).append('\n');
            }
        }
        return text.toString();
    }

    /** A registration that is nothing but its fields — the writer needs no more than that. */
    private record StubSchema(String key, String entityCode, List<ImportField> fields)
            implements ImportSchemaDefinition {

        @Override
        public ImportField naturalKey() {
            return fields.getFirst();
        }

        @Override
        public java.util.Map<String, java.util.Map<String, String>> findExisting(
                Set<String> naturalKeyValues) {
            throw new AssertionError("The template writer read the database.");
        }

        @Override
        public void upsert(ImportRow row, Long importBatchId) {
            throw new AssertionError("The template writer wrote to the database.");
        }

        @Override
        public ImportReversal reverse(long batchId) {
            throw new AssertionError("The template writer reversed a batch.");
        }
    }
}
