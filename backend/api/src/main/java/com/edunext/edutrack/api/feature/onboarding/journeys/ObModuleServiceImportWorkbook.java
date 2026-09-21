package com.edunext.edutrack.api.feature.onboarding.journeys;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * OB-07 · reads and writes the four-column workbook a Module Service's Steps,
 * Tasks and Checklists are bulk-authored from.
 *
 * <h2>One sheet, four columns, denormalised</h2>
 *
 * <p>{@code Module Service | Step | Task | Checklist}, one row per checklist
 * entry, with the first three repeated down the rows that share them. That is
 * a shape anybody can type without being taught a schema, and it is the whole
 * reason this replaced the three-sheet Tasks / Task List / Document Checklist
 * workbook: cross-sheet joins by name were the part authors got wrong.
 * {@link ObModuleServiceImportService} regroups the flat rows back into the
 * tree.
 *
 * <h2>The Step dropdown is a formula, not an inline list</h2>
 *
 * <p>Excel caps an <em>explicit</em> validation list at 255 characters of
 * joined values — fine for the six seeded stage names, and silently broken by
 * the first org that adds a few more. So the valid Steps are written to a
 * hidden {@code Lists} sheet and the constraint points at that range. The
 * three-sheet workbook this replaced used the explicit form; it is the one
 * part of it that did not generalise.
 *
 * <p>This class only moves cells in and out of a workbook. Every rule about
 * what a cell may contain lives in {@link ObModuleServiceImportService}.
 */
@Component
class ObModuleServiceImportWorkbook {

    static final String IMPORT_SHEET = "Import";
    private static final String INSTRUCTIONS_SHEET = "Instructions";
    private static final String LISTS_SHEET = "Lists";

    private static final List<String> HEADERS = List.of("Module Service", "Step", "Task", "Checklist");
    private static final List<Integer> COLUMN_WIDTHS = List.of(28, 26, 34, 38);

    /** Rows the Step dropdown covers. One file authors a catalogue, never thousands of rows. */
    private static final int DATA_ROWS = 500;

    /** Upper bound of the hidden list range. Far past any plausible stage master, harmless if sparse. */
    private static final int LIST_ROWS = 200;

    // ------------------------------------------------------------------
    // write
    // ------------------------------------------------------------------

    /**
     * @param stepNames the active OB-15 Implementation Stages, in display
     *                  order — the only values the Step column's dropdown, and
     *                  the import's own validation, accept. Read at download
     *                  time so the file can never offer a Step the import
     *                  would then reject.
     */
    void writeTemplate(List<String> stepNames, OutputStream out) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            try {
                CellStyle header = headerStyle(workbook);
                CellStyle text = textStyle(workbook);

                SXSSFSheet sheet = workbook.createSheet(IMPORT_SHEET);
                writeHeader(sheet, header);
                writeExampleRows(sheet, text, stepNames);
                sheet.createFreezePane(0, 1);
                addStepDropdown(sheet);

                writeInstructions(workbook, stepNames, header, text);
                writeLists(workbook, stepNames, header, text);
                workbook.setSheetHidden(workbook.getSheetIndex(LISTS_SHEET), true);

                workbook.write(out);
            } finally {
                // Deletes SXSSF's spilled temp files; left alone they live
                // until the JVM exits.
                workbook.dispose();
            }
        }
    }

    private static void writeHeader(SXSSFSheet sheet, CellStyle style) {
        Row row = sheet.createRow(0);
        for (int c = 0; c < HEADERS.size(); c++) {
            Cell cell = row.createCell(c);
            cell.setCellValue(HEADERS.get(c));
            cell.setCellStyle(style);
            sheet.setColumnWidth(c, COLUMN_WIDTHS.get(c) * 256);
        }
    }

    /**
     * A worked example, not filler. The second and third rows repeat one
     * Module Service / Step / Task with two different Checklist labels, which
     * is the only rule in the file an author has to be shown rather than told:
     * repeated rows are one task, not three.
     *
     * <p>The rows around them leave Checklist blank, which is the other half
     * of the same lesson and the shape most real files take: those tasks each
     * import with one checklist item named after the task. See
     * {@code ObModuleServiceImportService.TaskBuilder#checklistOrDefault}.
     */
    private static void writeExampleRows(SXSSFSheet sheet, CellStyle style, List<String> stepNames) {
        String first = stepNames.isEmpty() ? "" : stepNames.get(0);
        String second = stepNames.size() > 1 ? stepNames.get(1) : first;
        writeRow(sheet, 1, style, "Admission Management", second, "Enquiry Data Port", "");
        writeRow(sheet, 2, style, "Admission Management", second, "Student Data Port", "Validate source file");
        writeRow(sheet, 3, style, "Admission Management", second, "Student Data Port", "Reconcile record counts");
        writeRow(sheet, 4, style, "Admission Management", first, "Admission Form Setup", "");
        writeRow(sheet, 5, style, "Fee Management", first, "Fee Head Setup", "");
    }

    private static void writeRow(SXSSFSheet sheet, int rowIndex, CellStyle style, String... values) {
        Row row = sheet.createRow(rowIndex);
        for (int c = 0; c < values.length; c++) {
            Cell cell = row.createCell(c);
            cell.setCellValue(values[c]);
            cell.setCellStyle(style);
        }
    }

    private static void addStepDropdown(SXSSFSheet sheet) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint = helper.createFormulaListConstraint(
                LISTS_SHEET + "!$A$2:$A$" + LIST_ROWS);
        CellRangeAddressList range = new CellRangeAddressList(1, DATA_ROWS, 1, 1);
        DataValidation validation = helper.createValidation(constraint, range);
        validation.setShowErrorBox(true);
        validation.createErrorBox("Not a valid Step",
                "Pick a Step from the list. Steps come from the Implementation Stage master"
                        + " and cannot be created from this file.");
        sheet.addValidationData(validation);
    }

    private static void writeInstructions(SXSSFWorkbook workbook, List<String> stepNames,
                                          CellStyle header, CellStyle text) {
        Sheet sheet = workbook.createSheet(INSTRUCTIONS_SHEET);
        int r = writeLine(sheet, 0, "How this file is read", header);
        for (String line : List.of(
                "",
                "One sheet, four columns. Fill in the '" + IMPORT_SHEET + "' sheet and upload it back.",
                "",
                "1. Every row must carry Module Service, Step and Task. A blank cell in any of",
                "   the three is an error, not a continuation of the row above.",
                "2. Rows sharing the same Module Service + Step + Task become ONE task. Each",
                "   non-blank Checklist cell on those rows becomes one checklist item on it.",
                "3. Checklist is optional. Leave it blank and the task gets one checklist",
                "   item named after the task itself, so there is always something to tick.",
                "4. Order is file order - services, then steps, then tasks, then checklist items.",
                "5. 'Step' must be one of the Steps listed below. Steps are decided on the",
                "   Implementation Stage master and cannot be created from this file.",
                "6. The product is chosen in the import dialog, not in this file. One file",
                "   loads one product.",
                "7. A Module Service named here that does not exist yet is created as a new",
                "   draft. One that exists as a draft has its whole task tree REPLACED by this",
                "   file. One that is already published is refused - begin a revision first.",
                "8. Nothing is written until you confirm the preview, and nothing is published.",
                "")) {
            r = writeLine(sheet, r, line, text);
        }
        r = writeLine(sheet, r, "Set for you, editable in the designer afterwards", header);
        for (String line : List.of(
                "",
                "   TAT                1 day",
                "   Depends on         nothing - every task runs in parallel",
                "   Requires sign-off  No",
                "   Owner              the project's implementor",
                "   Checklist type     Check (documents are added in the designer)",
                "")) {
            r = writeLine(sheet, r, line, text);
        }
        r = writeLine(sheet, r, "Valid Steps", header);
        r = writeLine(sheet, r, "", text);
        for (String step : stepNames) {
            r = writeLine(sheet, r, "   " + step, text);
        }
        sheet.setColumnWidth(0, 88 * 256);
    }

    private static int writeLine(Sheet sheet, int rowIndex, String value, CellStyle style) {
        Cell cell = sheet.createRow(rowIndex).createCell(0);
        cell.setCellValue(value);
        cell.setCellStyle(style);
        return rowIndex + 1;
    }

    private static void writeLists(SXSSFWorkbook workbook, List<String> stepNames,
                                   CellStyle header, CellStyle text) {
        Sheet sheet = workbook.createSheet(LISTS_SHEET);
        int r = writeLine(sheet, 0, "Step", header);
        for (String step : stepNames) {
            r = writeLine(sheet, r, step, text);
        }
        sheet.setColumnWidth(0, 30 * 256);
    }

    private static CellStyle headerStyle(SXSSFWorkbook workbook) {
        Font bold = workbook.createFont();
        bold.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(bold);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    private static CellStyle textStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("@"));
        return style;
    }

    // ------------------------------------------------------------------
    // read
    // ------------------------------------------------------------------

    /** One data row, 1-based as Excel numbers it, for error messages a user can act on. */
    record RawRow(int rowNumber, List<String> cells) {
        String cell(int index) {
            return index < cells.size() ? cells.get(index) : "";
        }
    }

    /**
     * A workbook without an {@code Import} sheet reads as no rows rather than
     * a thrown error — a missing sheet, a renamed sheet and a sheet nobody
     * filled in are indistinguishable to a caller that only wants rows, and
     * {@link ObModuleServiceImportService} turns all three into one validation
     * error an admin can act on, which beats a stack trace naming a sheet.
     *
     * <p>Falls back to the <b>first</b> sheet when {@code Import} is absent,
     * because the commonest way to produce this file is Save As from another
     * tool, which does not preserve the sheet name.
     */
    List<RawRow> parse(InputStream in) throws IOException {
        try (org.apache.poi.ss.usermodel.Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheet(IMPORT_SHEET);
            if (sheet == null) {
                sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
            }
            if (sheet == null) {
                return List.of();
            }
            List<RawRow> rows = new ArrayList<>();
            int lastRow = sheet.getLastRowNum();
            // Row 0 is the header; data starts at index 1 (Excel row 2).
            for (int r = 1; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null || isBlankRow(row)) {
                    continue;
                }
                List<String> cells = new ArrayList<>(HEADERS.size());
                for (int c = 0; c < HEADERS.size(); c++) {
                    cells.add(cellText(row.getCell(c)));
                }
                rows.add(new RawRow(r + 1, cells));
            }
            return rows;
        }
    }

    private static boolean isBlankRow(Row row) {
        for (int c = 0; c < HEADERS.size(); c++) {
            if (!cellText(row.getCell(c)).isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * A numeric cell (Excel's default for anything that looks like a number)
     * read as plain digits — {@code 5.0} becomes {@code "5"}, never
     * {@code "5.0"}. A Module Service or Task called "2024" is ordinary, and
     * round-tripping it through a spreadsheet cell must not rename it.
     */
    private static String cellText(Cell cell) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            if (value == Math.rint(value) && !Double.isInfinite(value)) {
                return String.valueOf((long) value);
            }
            return String.valueOf(value);
        }
        return cell.toString().trim();
    }
}
