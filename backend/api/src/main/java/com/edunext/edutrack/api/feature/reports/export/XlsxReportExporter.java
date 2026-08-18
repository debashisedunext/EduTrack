package com.edunext.edutrack.api.feature.reports.export;

import com.edunext.edutrack.api.feature.reports.ReportDtos;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * A-064 · Excel, through POI's streaming writer.
 *
 * <h2>SXSSF, not XSSF — PLAN.md §2.2's requirement, on the write path</h2>
 *
 * <p>§2.2 says POI's streaming API "is required, not optional", and states it
 * for the import reader. The write path has the same problem in reverse:
 * {@code XSSFWorkbook} holds every row as an object graph until
 * {@code write()}, so a report over a year of A-073's 50,000 tickets is a heap
 * spike per concurrent export. {@link SXSSFWorkbook} keeps a sliding window of
 * rows in memory and flushes the rest to a temp file.
 *
 * <p>{@code dispose()} in the finally block is not optional bookkeeping — it
 * deletes that temp file. Without it a busy morning of exports fills the disk
 * with orphaned spool files that nothing else will ever clean up.
 *
 * <h2>Numbers are written as numbers</h2>
 *
 * <p>A figure written as a string is left-aligned, cannot be summed, and sorts
 * "10" before "9" — which is the difference between a spreadsheet somebody can
 * work with and one they retype. The column's declared {@code type} decides,
 * which is what that field on the contract is for.
 *
 * <h2>The formula guard applies here too, and matters more</h2>
 *
 * <p>A CSV cell beginning {@code =} is only dangerous because Excel interprets
 * it on import. An xlsx cell is a typed thing in a real workbook, so a string
 * that looks like a formula is the same risk with fewer steps. Same escape,
 * same reason — see {@link CsvReportExporter}.
 */
@Component
class XlsxReportExporter implements ReportExporter {

    /** POI's default window. Rows beyond it are flushed to disk rather than held. */
    private static final int ROW_WINDOW = 100;

    private static final String FORMULA_STARTERS = "=+-@\t\r";

    @Override
    public Format format() {
        return Format.XLSX;
    }

    @Override
    public void write(OutputStream out, String reportTitle, String appliedScope,
                      List<ReportDtos.Column> columns, List<Map<String, Object>> rows) throws Exception {

        SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_WINDOW);
        try {
            // WorkbookUtil rejects the characters Excel forbids in a sheet name
            // and caps it at 31 — "Delayed / SLA Breach" contains a slash and
            // would otherwise throw on a title nobody would think to test.
            Sheet sheet = workbook.createSheet(WorkbookUtil.createSafeSheetName(reportTitle));

            CellStyle title = boldStyle(workbook, 14);
            CellStyle header = headerStyle(workbook);

            int r = 0;

            Row titleRow = sheet.createRow(r++);
            titleRow.createCell(0).setCellValue(reportTitle);
            titleRow.getCell(0).setCellStyle(title);

            // Same provenance as the CSV, and for the same reason: the file
            // outlives the screen, and "your projects" is not recoverable from
            // the rows once it has been forwarded.
            Row scopeRow = sheet.createRow(r++);
            scopeRow.createCell(0)
                    .setCellValue("Scope: " + (appliedScope == null ? "not stated" : appliedScope));

            r++; // one blank row, so the header reads as a table header

            Row headerRow = sheet.createRow(r++);
            for (int c = 0; c < columns.size(); c++) {
                Cell cell = headerRow.createCell(c);
                cell.setCellValue(columns.get(c).label());
                cell.setCellStyle(header);
            }

            for (Map<String, Object> row : rows) {
                Row sheetRow = sheet.createRow(r++);
                for (int c = 0; c < columns.size(); c++) {
                    write(sheetRow.createCell(c), row.get(columns.get(c).key()), columns.get(c).type());
                }
            }

            // Widths are set from the header rather than autosized. autoSizeColumn
            // requires every row to still be in memory, which is exactly what the
            // streaming writer gives up — calling it here would either throw or
            // quietly measure only the window, sizing a column from whichever
            // hundred rows happened to be resident.
            for (int c = 0; c < columns.size(); c++) {
                sheet.setColumnWidth(c, Math.min(60, Math.max(12, columns.get(c).label().length() + 4)) * 256);
            }

            workbook.write(out);
        } finally {
            // Deletes the temp spool file. See the class note.
            workbook.dispose();
            workbook.close();
        }
    }

    private static void write(Cell cell, Object value, ReportDtos.ColumnType type) {
        if (value == null) {
            // Left genuinely blank rather than written as "—" or 0. A zero in a
            // numeric column is a measurement, and "nothing was recorded" is not.
            return;
        }

        switch (type) {
            case NUMBER, PERCENT, DURATION -> {
                if (value instanceof Number n) {
                    cell.setCellValue(n.doubleValue());
                } else if (value instanceof BigDecimal d) {
                    cell.setCellValue(d.doubleValue());
                } else {
                    // A value the column says is numeric and is not. Written as
                    // text rather than dropped or coerced: losing it hides a bug
                    // in whichever runner produced it.
                    cell.setCellValue(guard(String.valueOf(value)));
                }
            }
            // Dates are written as their ISO string rather than as an Excel
            // serial date. A serial needs a cell format to be legible at all,
            // and a wrong format silently shows 2026-08-17 as 46251 or, worse,
            // as a plausible different date under a locale that reads d/m as
            // m/d. ISO-8601 sorts correctly as text and is unambiguous.
            default -> cell.setCellValue(guard(String.valueOf(value)));
        }
    }

    /** See {@link CsvReportExporter#escape} — same threat, same mitigation. */
    private static String guard(String value) {
        if (!value.isEmpty() && FORMULA_STARTERS.indexOf(value.charAt(0)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    private static CellStyle boldStyle(Workbook workbook, int points) {
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) points);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        return style;
    }

    private static CellStyle headerStyle(Workbook workbook) {
        CellStyle style = boldStyle(workbook, 11);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.LEFT);
        return style;
    }
}
