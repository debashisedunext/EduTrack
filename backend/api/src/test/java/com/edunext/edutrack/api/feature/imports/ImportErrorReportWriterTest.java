package com.edunext.edutrack.api.feature.imports;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-036 · the error report, read back out of the bytes it was written into.
 *
 * <p>Through POI's reader rather than through the writer's own fields, for
 * {@code ImportTemplateWriterTest}'s reason: a test that asked the writer what
 * it had written would pass on a workbook Excel refuses to open, and "the report
 * downloads and then will not open" is a failure this feature cannot report to
 * itself.
 *
 * <p>Against {@link TestImportSchema} rather than the client master, for the
 * same reason every other engine test is: adding a column to the client master
 * must not break a test about the Reason column, and the point of the writer is
 * that it renders a schema it has never heard of.
 */
class ImportErrorReportWriterTest {

    private final ImportErrorReportWriter writer = new ImportErrorReportWriter();

    // ── the shape §4B.3 asks for ────────────────────────────────────────────

    @Test
    @DisplayName("the schema's own columns, with Row in front and Reason appended")
    void columnsAreTheSchemaPlusRowAndReason() throws IOException {
        Row header = sheet(rejected(5, "Code required", Map.of("name", "No Code"))).getRow(0);

        // §4B.3 says the Reason column is "appended", so it is last; Row is
        // first because it is what the user reads against their own spreadsheet.
        assertThat(cells(header))
                .containsExactly("Row", "Code", "Name", "Email", "Status", "Notes", "Reason");
    }

    /**
     * <b>The assertion this file exists for</b>, and the mirror of the one
     * {@code ImportTemplateWriterTest} makes about the template.
     *
     * <p>The blueprint's promise is that the user fixes these rows and re-uploads
     * <em>them</em>. That is only true if the report auto-maps completely on the
     * way back in — otherwise the corrected file lands on step 3 with a mapping
     * to rebuild, which is the work this file is meant to save.
     *
     * <p>Stated as a round trip rather than as "the headers are equal", because
     * the round trip is the property and it keeps holding if the matcher gets
     * smarter.
     */
    @Test
    @DisplayName("a report a user downloads auto-maps completely when they upload it back")
    void theReportRoundTripsThroughTheHeaderMatcher() throws IOException {
        ImportSchemaDefinition schema = new TestImportSchema();

        List<String> headers = cells(sheet(rejected(5, "Code required", Map.of())).getRow(0));
        ImportMapping mapping = HeaderMatcher.suggest(schema.fields(), headers);

        assertThat(mapping.missingRequired(schema.fields())).isEmpty();
        assertThat(mapping.targetToSource().keySet()).containsExactlyInAnyOrderElementsOf(
                schema.fields().stream().map(ImportField::name).toList());
    }

    @Test
    @DisplayName("Row and Reason map onto nothing, so a re-upload ignores them")
    void theTwoExtraColumnsAreNotMistakenForFields() throws IOException {
        ImportSchemaDefinition schema = new TestImportSchema();

        List<String> headers = cells(sheet(rejected(5, "Code required", Map.of())).getRow(0));
        ImportMapping mapping = HeaderMatcher.suggest(schema.fields(), headers);

        // The failure this guards against is quiet and expensive: a "Reason"
        // column that normalised onto some future field would import the
        // rejection message into the master on the re-upload.
        assertThat(mapping.targetToSource().values())
                .doesNotContain(ImportErrorReportWriter.ROW_COLUMN,
                        ImportErrorReportWriter.REASON_COLUMN);
    }

    // ── the rows ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("each row carries its source row number, its values and the engine's own reason")
    void rowsCarryTheNumberTheValuesAndTheReason() throws IOException {
        Sheet sheet = sheet(
                rejected(5, "Code: required", Map.of("name", "No Code Here")),
                duplicate(9, "Row 2 wins", Map.of("code", "ACME", "name", "Acme again")));

        assertThat(cells(sheet.getRow(1)))
                .containsExactly("5", "", "No Code Here", "", "", "", "Code: required");
        assertThat(cells(sheet.getRow(2)))
                .containsExactly("9", "ACME", "Acme again", "", "", "", "Row 2 wins");
    }

    /**
     * The reason is the engine's, not a rewording.
     *
     * <p>This is what carrying {@code reason} on the verdict since B-030 buys:
     * the sentence in the cell is the sentence step 4 showed the user before
     * they pressed Import. Two wordings of one refusal is how somebody comes to
     * believe the report describes a different run from the preview.
     */
    @Test
    @DisplayName("the reason is quoted verbatim, never rephrased")
    void theReasonIsTheEnginesOwnWords() throws IOException {
        String fromTheEngine = "Email: Invalid email address";

        Sheet sheet = sheet(rejected(4, fromTheEngine, Map.of("code", "ZEN", "email", "nope")));

        assertThat(lastCell(sheet.getRow(1))).isEqualTo(fromTheEngine);
    }

    @Test
    @DisplayName("a verdict with no reason still says something")
    void aMissingReasonFallsBackToTheCategory() throws IOException {
        Sheet sheet = sheet(new ImportRowVerdict(
                7, ImportVerdict.DUPLICATE_IN_FILE, null, Map.of("code", "ACME")));

        // Not reachable from the engine today. A blank cell here would be a row
        // in a report with no account of why it is in the report.
        assertThat(lastCell(sheet.getRow(1))).isEqualTo("Duplicate of an earlier row in this file");
    }

    /**
     * Every cell is text, which matters more here than in the template.
     *
     * <p>This file is generated from values that were already refused once. A
     * postal code losing its leading zero on the way <em>out</em> would reject
     * the row a second time on re-upload, for a fault the user cannot see and
     * did not make.
     */
    @Test
    @DisplayName("values are written as text, so a leading zero survives the round trip")
    void valuesAreTextNotNumbers() throws IOException {
        Sheet sheet = sheet(rejected(3, "Name: required", Map.of("code", "00123")));

        Cell code = sheet.getRow(1).getCell(1);
        assertThat(code.getCellType()).isEqualTo(CellType.STRING);
        assertThat(code.getStringCellValue()).isEqualTo("00123");
    }

    @Test
    @DisplayName("a column the caller never mapped is present and empty, not absent")
    void unmappedColumnsAreBlankRatherThanMissing() throws IOException {
        // The cost the class javadoc states plainly: the report is in template
        // shape, so every declared column has a cell whether or not the upload
        // carried one. A row with a short cell count would misalign the whole
        // sheet against the header.
        Sheet sheet = sheet(rejected(3, "Name: required", Map.of("code", "ACME")));

        assertThat(cells(sheet.getRow(1))).hasSize(7);
    }

    @Test
    @DisplayName("the sheet is named the same as the template's, so a re-upload needs no selector")
    void theSheetNameMatchesTheTemplates() throws IOException {
        TestImportSchema schema = new TestImportSchema();

        try (XSSFWorkbook workbook = read(writer.write(schema, List.of(
                rejected(3, "Name: required", Map.of("code", "ACME")))))) {
            // B-032 reads the first sheet by default. A different name would work
            // and would put a sheet selector in front of a user whose only job is
            // to fix six rows.
            assertThat(workbook.getSheetAt(0).getSheetName())
                    .isEqualTo(ImportTemplateWriter.dataSheetName(schema));
        }
    }

    // ── the name it is saved under ──────────────────────────────────────────

    @Test
    @DisplayName("the file name names the run, unlike the template's")
    void theFileNameCarriesTheBatchId() {
        // The opposite decision from ImportTemplateWriter.filename, and
        // deliberately: a template is the current shape of the schema and two
        // copies are the same file, while two error reports are two different
        // runs and a Downloads folder should say which.
        assertThat(ImportErrorReportWriter.fileName(new TestImportSchema(), 412))
                .isEqualTo("widgets-import-errors-412.xlsx");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Sheet sheet(ImportRowVerdict... failures) throws IOException {
        try (XSSFWorkbook workbook = read(writer.write(new TestImportSchema(), List.of(failures)))) {
            return workbook.getSheetAt(0);
        }
    }

    private static XSSFWorkbook read(byte[] bytes) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    private static ImportRowVerdict rejected(int rowNumber, String reason, Map<String, String> values) {
        return new ImportRowVerdict(rowNumber, ImportVerdict.REJECTED, reason, values);
    }

    private static ImportRowVerdict duplicate(int rowNumber, String reason, Map<String, String> values) {
        return new ImportRowVerdict(rowNumber, ImportVerdict.DUPLICATE_IN_FILE, reason, values);
    }

    /** Every cell of the row as a string, blanks included — alignment is the point. */
    private static List<String> cells(Row row) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < row.getLastCellNum(); i++) {
            Cell cell = row.getCell(i);
            values.add(cell == null ? "" : cell.getStringCellValue());
        }
        return values;
    }

    private static String lastCell(Row row) {
        return row.getCell(row.getLastCellNum() - 1).getStringCellValue();
    }
}
