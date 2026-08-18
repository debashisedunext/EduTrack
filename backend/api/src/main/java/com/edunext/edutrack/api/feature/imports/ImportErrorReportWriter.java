package com.edunext.edutrack.api.feature.imports;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * B-036 · blueprint §4B.3 step 5's error report — <b>"an .xlsx with a Reason
 * column appended, so the user can fix and re-upload just those rows."</b>
 *
 * <p>Nothing here names a client, for {@link ImportTemplateWriter}'s reason and
 * with the same consequence: B-038's resource import gets its error report from
 * a file that already exists. Every column comes from an {@link ImportField},
 * and every reason comes from a verdict the engine already reached.
 *
 * <h2>The report is written in the template's shape, not the upload's</h2>
 *
 * <p>This is the decision the whole task turns on. The columns are
 * {@link ImportField#header()} in template order — <em>not</em> the headings of
 * the file the user uploaded, and not the columns they happened to map.
 *
 * <p>The blueprint's promise is that the user fixes these rows and re-uploads
 * them. A report in template shape does that literally: {@link HeaderMatcher}
 * matches every column of it on the way back in, so the corrected file needs no
 * remapping and cannot be re-mapped wrongly. A report echoing the user's own
 * headings would look more faithful and would land them back on step 3 with a
 * mapping to rebuild, which is the step this file exists to save them.
 *
 * <p>What that costs is honest and worth stating: <b>columns the user did not
 * map are not in the report</b>, because they were never read — they are not on
 * the row, and inventing them empty would offer to clear values on re-upload
 * that the import cannot clear anyway. The {@code Row} column is what bridges
 * that: it names the row in the sheet they still have.
 *
 * <h2>Two extra columns, and both are ignored on the way back</h2>
 *
 * <pre>
 *   Row | Client Code | Name    | ... | Reason
 *    5  |             | Zenith  | ... | Client Code: required
 *    6  | ZENITH      | Zenith  | ... | Primary Email: not a valid email address
 * </pre>
 *
 * <p>{@code Row} is first because it is what the user reads against their own
 * spreadsheet, and {@code Reason} is last because §4B.3 says "appended". Neither
 * normalises onto any declared field or header, so {@link HeaderMatcher} leaves
 * both unmapped and a re-upload of this exact file imports the data columns and
 * ignores these two. That is checked rather than assumed —
 * {@code ImportErrorReportWriterTest} runs the round trip.
 *
 * <p>SXSSF and text-formatted cells, both for {@link ImportTemplateWriter}'s
 * reasons. The text format matters more here than there: this file is generated
 * <em>from values that were already refused once</em>, and a postal code losing
 * its leading zero on the way out would reject the row a second time for a fault
 * the user cannot see and did not make.
 */
@Component
class ImportErrorReportWriter {

    static final String ROW_COLUMN = "Row";
    static final String REASON_COLUMN = "Reason";

    /** Rows kept in memory before SXSSF spills to a temp file — B-031's window. */
    private static final int WINDOW = 100;

    /**
     * The report as bytes rather than streamed to an {@link java.io.OutputStream}.
     *
     * <p>{@link ImportTemplateWriter} streams because it writes straight to a
     * response; this is produced on a pool thread with no response in sight and
     * its next stop is {@link ImportReportStore#put}, which takes a byte array
     * because S3 needs a content length. Bounded by the 5,000-row upload cap and
     * built from rows already in heap.
     */
    byte[] write(ImportSchemaDefinition schema, List<ImportRowVerdict> failures) {
        List<ImportField> fields = schema.fields();

        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             SXSSFWorkbook workbook = new SXSSFWorkbook(WINDOW)) {
            try {
                // The same sheet name the template uses, so a re-upload of this
                // file lands on the sheet B-032 takes by default and the user
                // never meets the sheet selector.
                SXSSFSheet sheet = workbook.createSheet(ImportTemplateWriter.dataSheetName(schema));
                writeHeader(workbook, sheet, fields);
                writeRows(workbook, sheet, fields, failures);
                sheet.createFreezePane(0, 1);
                workbook.write(out);
            } finally {
                // Without this the spill files live until the JVM exits, which
                // on a long-running server means until the disk fills.
                workbook.dispose();
            }
            return out.toByteArray();

        } catch (IOException e) {
            // Writing to a byte array does not do IO in any sense a caller can
            // act on. Unchecked so the runner's own catch decides what a failed
            // report means, in one place, rather than every caller declaring it.
            throw new UncheckedIOException("Could not write the import error report", e);
        }
    }

    /**
     * The name the browser saves it as — <b>identified by batch id, unlike the
     * template</b>.
     *
     * <p>{@link ImportTemplateWriter#filename} is deliberately stable because a
     * template is the current shape of the schema and two copies in a Downloads
     * folder are the same file. This is the opposite: an error report is a
     * snapshot of one run, two of them are genuinely different files, and
     * {@code clients-import-errors (3).xlsx} would leave a user guessing which
     * import it came from.
     */
    static String fileName(ImportSchemaDefinition schema, long batchId) {
        return schema.key() + "-import-errors-" + batchId + ".xlsx";
    }

    // ------------------------------------------------------------------

    private static void writeHeader(SXSSFWorkbook workbook, SXSSFSheet sheet,
                                    List<ImportField> fields) {
        CellStyle headerStyle = headerStyle(workbook);
        CellStyle textStyle = textStyle(workbook);

        Row header = sheet.createRow(0);
        int column = 0;

        cell(header, column, ROW_COLUMN, headerStyle);
        sheet.setDefaultColumnStyle(column, textStyle);
        sheet.setColumnWidth(column++, 8 * 256);

        for (ImportField field : fields) {
            // Exactly field.header(), for the reason the template's header row
            // is: HeaderMatcher reads this back on the re-upload this file
            // exists to make possible.
            cell(header, column, field.header(), headerStyle);
            sheet.setDefaultColumnStyle(column, textStyle);
            sheet.setColumnWidth(column++, width(field));
        }

        cell(header, column, REASON_COLUMN, headerStyle);
        sheet.setDefaultColumnStyle(column, textStyle);
        // Wide: a reason is a sentence, and one that has to be widened before it
        // can be read is one the user resolves by guessing instead.
        sheet.setColumnWidth(column, 52 * 256);
    }

    private static void writeRows(SXSSFWorkbook workbook, SXSSFSheet sheet,
                                  List<ImportField> fields, List<ImportRowVerdict> failures) {
        CellStyle textStyle = textStyle(workbook);
        int rowIndex = 1;

        for (ImportRowVerdict failure : failures) {
            Row row = sheet.createRow(rowIndex++);
            int column = 0;

            cell(row, column++, String.valueOf(failure.rowNumber()), textStyle);
            for (ImportField field : fields) {
                // Absent rather than empty is ImportRow's rule for a blank cell,
                // and a blank cell is exactly what most rejections are about.
                String value = failure.values().get(field.name());
                cell(row, column++, value == null ? "" : value, textStyle);
            }
            cell(row, column, reason(failure), textStyle);
        }
    }

    /**
     * The engine's own words wherever it wrote any.
     *
     * <p>Not rephrased here, and this is the point of carrying {@code reason} on
     * the verdict since B-030: the sentence in this cell is the sentence the
     * step-4 preview showed the user before they pressed Import. Two wordings of
     * one refusal is how a user comes to believe the report describes a
     * different run.
     *
     * <p>The fallback covers a verdict with no reason — which the engine does not
     * produce for anything unwritable, and which a future one might. A cell
     * saying nothing is worse than a cell saying only the category.
     */
    private static String reason(ImportRowVerdict failure) {
        if (failure.reason() != null && !failure.reason().isBlank()) {
            return failure.reason();
        }
        return switch (failure.verdict()) {
            case DUPLICATE_IN_FILE -> "Duplicate of an earlier row in this file";
            case REJECTED -> "Rejected";
            // Not reachable: a report is built from rows that were not written.
            // Named anyway, because a switch that cannot describe its own input
            // is how a later verdict arrives here as an empty cell.
            case WILL_CREATE, WILL_UPDATE -> "Not imported";
        };
    }

    private static void cell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static CellStyle headerStyle(SXSSFWorkbook workbook) {
        Font bold = workbook.createFont();
        bold.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(bold);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setDataFormat(textFormat(workbook));
        return style;
    }

    private static CellStyle textStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(textFormat(workbook));
        return style;
    }

    /** Excel's built-in {@code @} — "treat this cell as text, whatever it looks like". */
    private static short textFormat(SXSSFWorkbook workbook) {
        return workbook.createDataFormat().getFormat("@");
    }

    private static int width(ImportField field) {
        int characters = Math.max(field.header().length(),
                field.example() == null ? 0 : field.example().length());
        return Math.min(Math.max(characters + 4, 14), 44) * 256;
    }
}
