package com.edunext.edutrack.api.feature.imports;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/**
 * B-031 · step 1 of S-34 — the template, generated from the schema itself.
 *
 * <p>Nothing here names a client. It is handed an {@link ImportSchemaDefinition}
 * and writes whatever that registration declares, which is what makes B-038's
 * resource template a file that already exists rather than a second writer.
 * Every value on the sheet comes from an {@link ImportField}: {@code header()} is
 * the column, {@code allowedValues()} is the dropdown, {@code example()} is the
 * worked row.
 *
 * <h2>The dropdown and the dry run read one declaration</h2>
 *
 * <p>Blueprint §4B.3 asks for "a data-validation dropdown on Status and Support
 * Plan". Those two columns are not named here and must not be: they are simply
 * the fields {@code ClientImportSchema} declared with {@code oneOf(...)}, and the
 * same list is what {@link ImportValidationEngine} checks an uploaded cell
 * against. Held as two lists — a template one and a validation one — they drift
 * the first time somebody adds a support plan, and the symptom is a file the
 * product itself handed the user being rejected row by row.
 *
 * <h2>SXSSF, not XSSF</h2>
 *
 * <p>The same rule {@code ResourceExportWriter} carries on the way out, and its
 * javadoc names this task as the way in. A template is small, so the memory
 * argument is weaker here than on a 5,000-row export — but the reason to use the
 * streaming writer anyway is that this is the file B-036's error report and
 * B-038's second template are both written by the same hands, and a package with
 * one DOM writer in it is a package where the next one is a DOM writer too.
 *
 * <h2>Two decisions that look cosmetic and are not</h2>
 *
 * <p><b>The header row is exactly {@link ImportField#header()}, undecorated.</b>
 * Marking required columns with an asterisk, a colour word or a "(required)"
 * suffix was the obvious first draft and it breaks step 3:
 * {@link HeaderMatcher} matches on that text, so the file this product handed the
 * user would fail to auto-match when they uploaded it back, and every column
 * would land in B-033's manual override dropdown. Required-ness is on the
 * Instructions sheet instead, where it cannot be mistaken for data.
 * {@code ImportTemplateWriterTest} pins the round trip rather than the intention.
 *
 * <p><b>Every column is text-formatted.</b> Left to Excel's general format a
 * client code of {@code 00123} is stored as the number 123, a postal code loses
 * its leading zero and a phone number of {@code +91 22 4000 1000} can arrive as a
 * formula error. The row is then rejected at step 4 for a value the user never
 * typed, in a file they cannot see anything wrong with.
 */
@Component
class ImportTemplateWriter {

    /**
     * Rows the dropdowns cover, matching the 5,000-row upload cap in the
     * contract (B-032). Validation on a range wider than the import will ever
     * accept would promise Excel-side help on rows the server refuses.
     */
    private static final int DATA_ROWS = 5_000;

    /**
     * Excel's ceiling on an explicit-list data-validation formula, including the
     * quotes and separators. Beyond it the constraint is silently dropped by
     * some readers and rejects the file outright in others.
     */
    private static final int EXPLICIT_LIST_LIMIT = 255;

    /** Rows kept in memory before SXSSF spills to a temp file. */
    private static final int WINDOW = 100;

    private static final String INSTRUCTIONS_SHEET = "Instructions";

    void write(ImportSchemaDefinition schema, OutputStream out) throws IOException {
        List<ImportField> fields = schema.fields();
        checkDropdownsFit(schema, fields);

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(WINDOW)) {
            try {
                // The data sheet is created first so it is sheet 0: B-032 takes
                // the first sheet by default, and a user who never opens the
                // sheet selector must land on the one they fill in.
                SXSSFSheet data = workbook.createSheet(dataSheetName(schema));
                writeDataSheet(workbook, data, fields);
                writeInstructionsSheet(workbook, schema, fields);
                workbook.write(out);
            } finally {
                // Deletes the temp files SXSSF spilled rows into. Without this
                // they live until the JVM exits, which on a long-running server
                // means until the disk fills.
                workbook.dispose();
            }
        }
    }

    /** {@code clients} → {@code Clients}, and the same for any future registration. */
    static String dataSheetName(ImportSchemaDefinition schema) {
        String key = schema.key();
        String titled = Character.toUpperCase(key.charAt(0)) + key.substring(1).toLowerCase(Locale.ROOT);
        // Excel refuses []:*?/\ and anything over 31 characters. A registration
        // key is a URL segment so it is already safe, but a template that fails
        // to open is a worse way to find out that stopped being true.
        return WorkbookUtil.createSafeSheetName(titled);
    }

    /**
     * The name the browser saves it as — stable, and deliberately not dated the
     * way {@code ResourceExportWriter}'s export is. An export is a snapshot of
     * rows at a moment and two of them are different files; a template is the
     * current shape of the schema, and a Downloads folder holding
     * {@code clients-import-template (4).xlsx} tells nobody which one matches
     * the columns the server accepts today.
     */
    static String filename(ImportSchemaDefinition schema) {
        return schema.key() + "-import-template.xlsx";
    }

    // ------------------------------------------------------------------
    // the sheet the user fills in
    // ------------------------------------------------------------------

    private static void writeDataSheet(SXSSFWorkbook workbook, SXSSFSheet sheet,
                                       List<ImportField> fields) {

        CellStyle headerStyle = headerStyle(workbook);
        CellStyle textStyle = textStyle(workbook);

        Row header = sheet.createRow(0);
        for (int column = 0; column < fields.size(); column++) {
            ImportField field = fields.get(column);
            // Exactly field.header(). See the class javadoc: HeaderMatcher reads
            // this back, so anything appended here breaks step 3's auto-match.
            Cell cell = header.createCell(column);
            cell.setCellValue(field.header());
            cell.setCellStyle(headerStyle);

            sheet.setDefaultColumnStyle(column, textStyle);
            sheet.setColumnWidth(column, width(field));
        }

        // Blueprint §4B.3: "one filled example row". A template with a worked
        // example produces far fewer rejected rows than one with only headers —
        // and the Instructions sheet says in as many words that it is an example
        // and has to go, because a user who leaves it creates ACME Corporation.
        Row example = sheet.createRow(1);
        for (int column = 0; column < fields.size(); column++) {
            String value = fields.get(column).example();
            Cell cell = example.createCell(column);
            cell.setCellStyle(textStyle);
            if (value != null) {
                cell.setCellValue(value);
            }
        }

        // The header only. Freezing the example row as well would keep a row
        // the Instructions sheet tells them to delete pinned to the top of the
        // screen for the whole of the job.
        //
        // Set while row 0 is still inside SXSSF's window: a flushed row cannot
        // be revisited, so anything touching the header happens before the data
        // does.
        sheet.createFreezePane(0, 1);

        addDropdowns(sheet, fields);
    }

    /**
     * One dropdown per {@code ENUM} column, over exactly the field's
     * {@code allowedValues}.
     *
     * <p>{@code setShowErrorBox(true)} is what makes it a constraint rather than
     * a suggestion — without it Excel offers the list and accepts anything typed
     * past it, which is the worst of both: the user believes the cell was
     * checked and step 4 rejects it anyway.
     */
    private static void addDropdowns(SXSSFSheet sheet, List<ImportField> fields) {
        DataValidationHelper helper = sheet.getDataValidationHelper();

        for (int column = 0; column < fields.size(); column++) {
            ImportField field = fields.get(column);
            if (field.type() != ImportFieldType.ENUM) {
                continue;
            }

            DataValidationConstraint constraint = helper.createExplicitListConstraint(
                    field.allowedValues().toArray(String[]::new));
            CellRangeAddressList range =
                    new CellRangeAddressList(1, DATA_ROWS, column, column);

            DataValidation validation = helper.createValidation(constraint, range);
            validation.setShowErrorBox(true);
            validation.setSuppressDropDownArrow(false);
            validation.createErrorBox(field.header(),
                    "Choose one of: " + String.join(", ", field.allowedValues()));
            sheet.addValidationData(validation);
        }
    }

    /**
     * Refuses to write a template whose dropdown Excel cannot carry.
     *
     * <p>The alternative — write the sheet without that dropdown — is the
     * failure this package is least able to afford: the column would look like
     * free text, the user would type their own spelling, and the rejection would
     * arrive at step 4 pointing at a cell the template never constrained. A
     * schema this large needs a hidden lookup sheet and a formula constraint,
     * which is a real change to make deliberately rather than a degradation to
     * discover.
     *
     * <p>Today's registrations are nowhere near it —
     * {@code ImportTemplateWriterTest} asserts that for every registered schema,
     * so this throws for a schema somebody adds, not for one that exists.
     */
    private static void checkDropdownsFit(ImportSchemaDefinition schema, List<ImportField> fields) {
        for (ImportField field : fields) {
            if (field.type() != ImportFieldType.ENUM) {
                continue;
            }
            // Excel stores the list as "A,B,C" — the quotes and the separators
            // count against the limit, so the formula is measured, not the values.
            int formulaLength = String.join(",", field.allowedValues()).length() + 2;
            if (formulaLength > EXPLICIT_LIST_LIMIT) {
                throw new IllegalStateException(
                        "Import schema '" + schema.key() + "' declares field '" + field.name()
                                + "' with " + field.allowedValues().size()
                                + " allowed values (" + formulaLength + " characters). Excel's"
                                + " explicit-list limit is " + EXPLICIT_LIST_LIMIT
                                + "; this column needs a hidden lookup sheet and a formula"
                                + " constraint rather than a template with no dropdown on it.");
            }
        }
    }

    // ------------------------------------------------------------------
    // the sheet that explains it
    // ------------------------------------------------------------------

    /**
     * Everything the header row deliberately does not say.
     *
     * <p>A second sheet rather than a comment on each header cell: cell comments
     * are invisible in Google Sheets' import, in LibreOffice's default view and
     * on any printout, and they are the first thing lost when somebody copies
     * the columns into a workbook of their own. This sheet survives all three.
     */
    private static void writeInstructionsSheet(SXSSFWorkbook workbook,
                                               ImportSchemaDefinition schema,
                                               List<ImportField> fields) {

        SXSSFSheet sheet = workbook.createSheet(INSTRUCTIONS_SHEET);
        CellStyle headerStyle = headerStyle(workbook);
        CellStyle textStyle = textStyle(workbook);
        CellStyle wrapStyle = wrapStyle(workbook);

        int rowIndex = 0;
        for (String line : preamble(schema)) {
            Cell cell = sheet.createRow(rowIndex++).createCell(0);
            cell.setCellValue(line);
            cell.setCellStyle(textStyle);
        }
        rowIndex++;

        Row header = sheet.createRow(rowIndex++);
        List<String> columns = List.of(
                "Column", "Required", "Format", "Max length", "Allowed values", "Example");
        for (int i = 0; i < columns.size(); i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(columns.get(i));
            cell.setCellStyle(headerStyle);
        }

        for (ImportField field : fields) {
            Row row = sheet.createRow(rowIndex++);
            int column = 0;
            cell(row, column++, field.header(), textStyle);
            cell(row, column++, field.required() ? "Required" : "Optional", textStyle);
            cell(row, column++, format(field), textStyle);
            cell(row, column++, field.maxLength() > 0 ? String.valueOf(field.maxLength()) : "",
                    textStyle);
            cell(row, column++, String.join(", ", field.allowedValues()), wrapStyle);
            cell(row, column, field.example() == null ? "" : field.example(), textStyle);
        }

        sheet.setColumnWidth(0, 26 * 256);
        sheet.setColumnWidth(1, 12 * 256);
        sheet.setColumnWidth(2, 22 * 256);
        sheet.setColumnWidth(3, 12 * 256);
        sheet.setColumnWidth(4, 34 * 256);
        sheet.setColumnWidth(5, 26 * 256);
    }

    /**
     * The three things a user has to know before they type anything, and the
     * natural-key line is the one that matters most: an importer that updates
     * silently on a key nobody was told about is how a spreadsheet meant to add
     * forty clients overwrites four hundred.
     */
    private static List<String> preamble(ImportSchemaDefinition schema) {
        return List.of(
                "How to use this template",
                "1. Fill in the '" + dataSheetName(schema) + "' sheet. Keep the header row"
                        + " exactly as it is — the column names are how the import recognises"
                        + " your data.",
                "2. Row 2 is a worked example. Replace it with your own data or delete it —"
                        + " it is imported like any other row if you leave it.",
                "3. '" + naturalKeyHeader(schema) + "' decides create or update: a value that"
                        + " already exists updates that record, it never creates a second one.",
                "4. Blank cells leave the existing value alone on an update. The import cannot"
                        + " clear a field — do that on the record itself.",
                "5. Nothing is written until you confirm the preview. Step 4 shows every row's"
                        + " outcome first.");
    }

    private static String naturalKeyHeader(ImportSchemaDefinition schema) {
        return schema.naturalKey().header();
    }

    /** Plain words rather than the enum name: this sheet is read by whoever fills the file in. */
    private static String format(ImportField field) {
        return switch (field.type()) {
            case TEXT -> "Text";
            case EMAIL -> "Email address";
            case DATE -> "Date, YYYY-MM-DD";
            case ENUM -> "One of the values listed";
            case INTEGER -> "Whole number";
        };
    }

    // ------------------------------------------------------------------
    // styles and helpers
    // ------------------------------------------------------------------

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
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setDataFormat(textFormat(workbook));
        return style;
    }

    private static CellStyle textStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(textFormat(workbook));
        return style;
    }

    private static CellStyle wrapStyle(SXSSFWorkbook workbook) {
        CellStyle style = textStyle(workbook);
        style.setWrapText(true);
        return style;
    }

    /** Excel's built-in {@code @} — "treat this cell as text, whatever it looks like". */
    private static short textFormat(SXSSFWorkbook workbook) {
        return workbook.createDataFormat().getFormat("@");
    }

    /**
     * Wide enough for the header and the example, because a column showing
     * {@code ########} is the first thing a user tries to fix and the last thing
     * they should have to.
     */
    private static int width(ImportField field) {
        int characters = Math.max(field.header().length(),
                field.example() == null ? 0 : field.example().length());
        return Math.min(Math.max(characters + 4, 14), 44) * 256;
    }
}
