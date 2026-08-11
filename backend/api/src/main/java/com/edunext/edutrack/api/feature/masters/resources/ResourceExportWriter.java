package com.edunext.edutrack.api.feature.masters.resources;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * B-010 · the S-07 export, in the two formats the contract offers.
 *
 * <p><b>SXSSF, not XSSF.</b> The DOM writer builds the entire sheet in memory
 * before a byte is emitted; two admins exporting a five-thousand-person
 * directory at once is then two full workbooks resident at the same time. SXSSF
 * keeps a sliding window of {@value #WINDOW} rows and flushes the rest to a temp
 * file, so memory is flat in the number of rows. This is the same rule B-031
 * carries for the import template — one engine's worth of reasoning, applied on
 * the way out as well as in.
 *
 * <p>Both writers consume the same batches from
 * {@link ResourceService#streamAll}, so the file and the screen cannot disagree
 * about what a filter means.
 */
@Component
class ResourceExportWriter {

    /** Rows kept in memory; the rest are flushed to a temp file as they are written. */
    private static final int WINDOW = 100;

    /**
     * Column order matches the S-07 grid left to right, so the file reads like
     * the screen it came from. Username and designation are included although
     * the grid hides them: an export is what people reconcile against a payroll
     * or directory extract, and both are needed for that and cost nothing here.
     */
    private static final List<String> HEADERS = List.of(
            "Emp Code", "Name", "Username", "Email", "Role", "Department", "Designation",
            "Reporting Manager", "Projects", "Status", "Open Tickets", "Last Login (UTC)");

    /**
     * Characters Excel and Sheets treat as the start of a formula.
     *
     * <p>A resource named {@code =cmd|…} — or, far more likely, a department
     * typed as {@code -Ops}, which Excel reads as a negation — is inert in the
     * database and executable in a spreadsheet. Every cell of text this writer
     * emits came from a form somebody filled in, so every one is prefixed when
     * it starts with one of these.
     */
    private static final String FORMULA_LEADS = "=+-@\t\r";

    /**
     * The UTF-8 byte-order mark, as bytes rather than as a {@code char}.
     *
     * <p>Written straight to the stream ahead of the writer, and spelled out in
     * hex: a literal U+FEFF in source is invisible, and the next person to
     * reformat or copy this line would have no way of seeing what they lost.
     */
    private static final byte[] BYTE_ORDER_MARK = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final ResourceService resources;

    ResourceExportWriter(ResourceService resources) {
        this.resources = resources;
    }

    // ------------------------------------------------------------------
    // xlsx
    // ------------------------------------------------------------------

    void writeXlsx(ResourceFilter filter, OutputStream out) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(WINDOW)) {
            try {
                SXSSFSheet sheet = workbook.createSheet("Resources");
                writeHeaderRow(workbook, sheet);
                // Set while row 0 is still inside the window. SXSSF will not let
                // a flushed row be revisited, so anything touching the header
                // has to happen before the data does.
                sheet.createFreezePane(0, 1);

                // A counter would have to be effectively final to be captured by
                // the lambda; a one-element array is the usual idiom and keeps
                // the row index next to the writing.
                int[] rowIndex = {1};
                resources.streamAll(filter, batch -> {
                    for (ResourceDtos.Resource resource : batch) {
                        writeRow(sheet.createRow(rowIndex[0]++), resource);
                    }
                });

                workbook.write(out);
            } finally {
                // Deletes the temp files SXSSF spilled rows into. Without this
                // they survive until the JVM exits, which on a long-running
                // server means until the disk fills.
                workbook.dispose();
            }
        }
    }

    private static void writeHeaderRow(SXSSFWorkbook workbook, SXSSFSheet sheet) {
        Font bold = workbook.createFont();
        bold.setBold(true);
        CellStyle headerStyle = workbook.createCellStyle();
        headerStyle.setFont(bold);

        Row header = sheet.createRow(0);
        for (int i = 0; i < HEADERS.size(); i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(HEADERS.get(i));
            cell.setCellStyle(headerStyle);
        }
    }

    private static void writeRow(Row row, ResourceDtos.Resource resource) {
        int column = 0;
        text(row, column++, resource.employeeCode());
        text(row, column++, resource.displayName());
        text(row, column++, resource.username());
        text(row, column++, resource.email());
        text(row, column++, resource.role());
        text(row, column++, resource.department());
        text(row, column++, resource.designation());
        text(row, column++, managerName(resource));
        text(row, column++, projectNames(resource));
        text(row, column++, status(resource));
        row.createCell(column++).setCellValue(
                resource.openTicketCount() == null ? 0 : resource.openTicketCount());
        text(row, column, lastLogin(resource));
    }

    private static void text(Row row, int column, String value) {
        row.createCell(column).setCellValue(neutralise(value));
    }

    // ------------------------------------------------------------------
    // csv
    // ------------------------------------------------------------------

    /**
     * RFC 4180, with a UTF-8 byte-order mark.
     *
     * <p>The BOM is there because Excel opens a BOM-less UTF-8 CSV in the
     * system codepage, which renders every non-ASCII name as mojibake — and the
     * directory this exports is full of them.
     */
    void writeCsv(ResourceFilter filter, OutputStream out) throws IOException {
        out.write(BYTE_ORDER_MARK);

        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        writeCsvRow(writer, HEADERS);

        try {
            resources.streamAll(filter, batch -> {
                try {
                    for (ResourceDtos.Resource resource : batch) {
                        writeCsvRow(writer, csvCells(resource));
                    }
                } catch (IOException e) {
                    // The sink signature cannot throw; unwrapped again below so
                    // the caller still sees an IOException.
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        writer.flush();
    }

    private static List<String> csvCells(ResourceDtos.Resource resource) {
        return List.of(
                nullToEmpty(resource.employeeCode()),
                nullToEmpty(resource.displayName()),
                nullToEmpty(resource.username()),
                nullToEmpty(resource.email()),
                nullToEmpty(resource.role()),
                nullToEmpty(resource.department()),
                nullToEmpty(resource.designation()),
                managerName(resource),
                projectNames(resource),
                status(resource),
                String.valueOf(resource.openTicketCount() == null ? 0 : resource.openTicketCount()),
                lastLogin(resource));
    }

    private static void writeCsvRow(Writer writer, List<String> cells) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(quote(neutralise(cells.get(i))));
        }
        writer.write(line.append("\r\n").toString());
    }

    /** RFC 4180: quote always, and double an embedded quote. */
    private static String quote(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    // ------------------------------------------------------------------
    // shared cell values
    // ------------------------------------------------------------------

    private static String managerName(ResourceDtos.Resource resource) {
        return resource.reportingManager() == null ? "" : resource.reportingManager().displayName();
    }

    private static String projectNames(ResourceDtos.Resource resource) {
        if (resource.projects() == null || resource.projects().isEmpty()) {
            return "";
        }
        return resource.projects().stream()
                .map(ResourceDtos.ProjectRef::name)
                .collect(Collectors.joining(", "));
    }

    /**
     * The word, not the boolean. A column of {@code TRUE}/{@code FALSE} is
     * ambiguous the moment the header scrolls off — active what?
     */
    private static String status(ResourceDtos.Resource resource) {
        return Boolean.TRUE.equals(resource.isActive()) ? "Active" : "Inactive";
    }

    private static String lastLogin(ResourceDtos.Resource resource) {
        Instant lastLoginAt = resource.lastLoginAt();
        return lastLoginAt == null ? "" : DateTimeFormatter.ISO_INSTANT.format(lastLoginAt);
    }

    /**
     * Renders a value inert as a spreadsheet formula, leaving it readable.
     *
     * <p>A leading apostrophe is the spreadsheet convention for "this is text";
     * Excel and Sheets both consume it on open rather than displaying it.
     */
    private static String neutralise(String value) {
        String text = nullToEmpty(value);
        if (!text.isEmpty() && FORMULA_LEADS.indexOf(text.charAt(0)) >= 0) {
            return "'" + text;
        }
        return text;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
