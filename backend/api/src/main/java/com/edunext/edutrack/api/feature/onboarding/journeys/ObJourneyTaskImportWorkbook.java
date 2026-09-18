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
 * OB-07 · reads and writes the three-sheet workbook a Module Service's Tasks,
 * Task List and Document Checklist are bulk-authored from.
 *
 * <p>Deliberately not built on {@code feature/imports}' schema-driven engine.
 * That engine upserts one flat entity per row against a single natural key —
 * right for Clients and Resources, wrong here: a Module Service's task tree is
 * a three-level parent/child/grandchild shape with no business key of its own
 * (nobody invents a code for a checklist row), and creating a task correctly
 * has to go through {@code ObJourneyTemplateService} — sequencing, the
 * draft/publish guard, stage-group membership — not a raw column upsert. See
 * {@link ObJourneyTaskImportService} for the rest of that reasoning.
 *
 * <p>This class only moves cells in and out of a workbook. Every rule about
 * what a cell may contain, and what the file as a whole must add up to, lives
 * in {@link ObJourneyTaskImportService}.
 */
@Component
class ObJourneyTaskImportWorkbook {

    static final String TASKS_SHEET = "Tasks";
    static final String ITEMS_SHEET = "Task List";
    static final String DOCS_SHEET = "Document Checklist";
    private static final String INSTRUCTIONS_SHEET = "Instructions";

    private static final List<String> TASK_HEADERS = List.of(
            "Stage", "Task Name", "Description", "TAT Days", "Requires Sign-off (Y/N)", "Depends On Task");
    private static final List<String> ITEM_HEADERS =
            List.of("Task Name", "Item Label", "Mandatory (Y/N)");
    private static final List<String> DOC_HEADERS =
            List.of("Task Name", "Document Label", "Required (Y/N)");

    /** Rows a dropdown covers. A template feeds one Module Service, never thousands of rows. */
    private static final int DATA_ROWS = 500;

    // ------------------------------------------------------------------
    // write
    // ------------------------------------------------------------------

    /**
     * @param stageNames this template's current stage groups, in display order —
     *                    the only values the "Stage" column's dropdown, and the
     *                    import's own validation, accept. Never empty: every
     *                    Module Service is seeded with the active implementation
     *                    stages the moment it is created.
     */
    void writeTemplate(List<String> stageNames, OutputStream out) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            try {
                CellStyle header = headerStyle(workbook);
                CellStyle text = textStyle(workbook);

                SXSSFSheet tasks = workbook.createSheet(TASKS_SHEET);
                writeHeader(tasks, TASK_HEADERS, header);
                writeRow(tasks, 1, text,
                        stageNames.get(0), "Kickoff call", "Introduce the account team", "1", "N", "");
                tasks.createFreezePane(0, 1);
                addExplicitDropdown(tasks, 0, stageNames);
                addYesNoDropdown(tasks, 4);

                SXSSFSheet items = workbook.createSheet(ITEMS_SHEET);
                writeHeader(items, ITEM_HEADERS, header);
                writeRow(items, 1, text, "Kickoff call", "Signed order form received", "Y");
                items.createFreezePane(0, 1);
                addYesNoDropdown(items, 2);

                SXSSFSheet docs = workbook.createSheet(DOCS_SHEET);
                writeHeader(docs, DOC_HEADERS, header);
                writeRow(docs, 1, text, "Kickoff call", "Signed requirement sheet", "Y");
                docs.createFreezePane(0, 1);
                addYesNoDropdown(docs, 2);

                writeInstructions(workbook, stageNames, header, text);
                workbook.write(out);
            } finally {
                // Deletes SXSSF's spilled temp files; left alone they live until
                // the JVM exits.
                workbook.dispose();
            }
        }
    }

    private static void writeHeader(SXSSFSheet sheet, List<String> headers, CellStyle style) {
        Row row = sheet.createRow(0);
        for (int c = 0; c < headers.size(); c++) {
            Cell cell = row.createCell(c);
            cell.setCellValue(headers.get(c));
            cell.setCellStyle(style);
            sheet.setColumnWidth(c, Math.min(Math.max(headers.get(c).length() + 6, 14), 40) * 256);
        }
    }

    private static void writeRow(SXSSFSheet sheet, int rowIndex, CellStyle style, String... values) {
        Row row = sheet.createRow(rowIndex);
        for (int c = 0; c < values.length; c++) {
            Cell cell = row.createCell(c);
            cell.setCellValue(values[c]);
            cell.setCellStyle(style);
        }
    }

    private static void addExplicitDropdown(SXSSFSheet sheet, int column, List<String> values) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint = helper.createExplicitListConstraint(values.toArray(String[]::new));
        CellRangeAddressList range = new CellRangeAddressList(1, DATA_ROWS, column, column);
        DataValidation validation = helper.createValidation(constraint, range);
        validation.setShowErrorBox(true);
        sheet.addValidationData(validation);
    }

    private static void addYesNoDropdown(SXSSFSheet sheet, int column) {
        addExplicitDropdown(sheet, column, List.of("Y", "N"));
    }

    private static void writeInstructions(SXSSFWorkbook workbook, List<String> stageNames,
                                          CellStyle header, CellStyle text) {
        Sheet sheet = workbook.createSheet(INSTRUCTIONS_SHEET);
        int r = 0;
        for (String line : List.of(
                "How to use this template",
                "1. 'Tasks' is one row per task. 'Stage' must be one of this Module Service's",
                "   existing stages (listed below) or blank, which files the task under",
                "   \"Ungrouped\". Order is not read from a column: tasks run top to bottom",
                "   within the file, and 'Depends On Task' may only name a task that appears",
                "   earlier in this sheet.",
                "2. 'Task List' and 'Document Checklist' add checklist rows to a task named",
                "   in the Tasks sheet — as many or as few as that task needs.",
                "3. Y/N columns default to N if left blank, except Mandatory and Required,",
                "   which default to Y.",
                "4. Importing REPLACES this draft's entire task tree with what is in the",
                "   file. Nothing is written until you confirm the preview.",
                "",
                "This Module Service's current stages:")) {
            Cell cell = sheet.createRow(r++).createCell(0);
            cell.setCellValue(line);
            cell.setCellStyle(text);
        }
        for (String stage : stageNames) {
            Cell cell = sheet.createRow(r++).createCell(0);
            cell.setCellValue("- " + stage);
            cell.setCellStyle(text);
        }
        sheet.setColumnWidth(0, 90 * 256);
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

    record ParsedWorkbook(List<RawRow> tasks, List<RawRow> items, List<RawRow> docs) {
    }

    /**
     * A sheet this workbook does not have reads as empty rather than a thrown
     * error — a missing or misnamed sheet and a sheet nobody filled in look
     * identical to a caller who only wants rows, and {@link ObJourneyTaskImportService}
     * turns "the Tasks sheet has no rows" into one validation error either way,
     * which is a plainer thing for an admin to act on than a stack trace naming
     * a sheet.
     */
    ParsedWorkbook parse(InputStream in) throws IOException {
        try (org.apache.poi.ss.usermodel.Workbook workbook = WorkbookFactory.create(in)) {
            return new ParsedWorkbook(
                    readSheet(workbook, TASKS_SHEET, TASK_HEADERS.size()),
                    readSheet(workbook, ITEMS_SHEET, ITEM_HEADERS.size()),
                    readSheet(workbook, DOCS_SHEET, DOC_HEADERS.size()));
        }
    }

    private static List<RawRow> readSheet(org.apache.poi.ss.usermodel.Workbook workbook,
                                          String sheetName, int columns) {
        Sheet sheet = workbook.getSheet(sheetName);
        if (sheet == null) {
            return List.of();
        }
        List<RawRow> rows = new ArrayList<>();
        int lastRow = sheet.getLastRowNum();
        // Row 0 is the header; data starts at index 1 (Excel row 2).
        for (int r = 1; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null || isBlankRow(row, columns)) {
                continue;
            }
            List<String> cells = new ArrayList<>(columns);
            for (int c = 0; c < columns; c++) {
                cells.add(cellText(row.getCell(c)));
            }
            rows.add(new RawRow(r + 1, cells));
        }
        return rows;
    }

    private static boolean isBlankRow(Row row, int columns) {
        for (int c = 0; c < columns; c++) {
            if (!cellText(row.getCell(c)).isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * A numeric cell (Excel's default for anything that looks like a number)
     * read as plain digits — {@code 5.0} becomes {@code "5"}, never
     * {@code "5.0"} — so a TAT of five days does not fail an integer parse
     * downstream for the crime of round-tripping through a spreadsheet cell.
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
