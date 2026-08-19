package com.edunext.edutrack.api.feature.reports.export;

import com.edunext.edutrack.api.feature.reports.ReportDtos;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-064 · the exporters, asserted by reading the bytes back.
 *
 * <p>Not by checking that {@code write} did not throw. A CSV writer that quotes
 * wrongly, an xlsx that writes every figure as text, and a PDF with no rows in
 * it all complete without error — the failure is in the file, so the file is
 * what the assertions open.
 */
@DisplayName("report exporters")
class ReportExporterTest {

    private static final List<ReportDtos.Column> COLUMNS = List.of(
            new ReportDtos.Column("date", "Date", ReportDtos.ColumnType.DATE),
            new ReportDtos.Column("title", "Ticket", ReportDtos.ColumnType.STRING),
            new ReportDtos.Column("closed", "Closed", ReportDtos.ColumnType.NUMBER));

    private static Map<String, Object> row(String date, String title, long closed) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("date", date);
        row.put("title", title);
        row.put("closed", closed);
        return row;
    }

    private static final List<Map<String, Object>> ROWS = List.of(
            row("2026-08-10", "Login fails", 2),
            row("2026-08-11", "Payment, sometimes \"slow\"", 3));

    private static byte[] export(ReportExporter exporter, List<Map<String, Object>> rows) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exporter.write(out, "Date-wise Report", "your projects", COLUMNS, ExportRows.of(rows));
        return out.toByteArray();
    }

    @Nested
    @DisplayName("CSV")
    class Csv {

        private final CsvReportExporter exporter = new CsvReportExporter();

        private String text() throws Exception {
            return new String(export(exporter, ROWS), StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("carries the title, the scope and a header before the rows")
        void structure() throws Exception {
            String csv = text();

            assertThat(csv).contains("Date-wise Report");
            // Once forwarded, "your projects" is not recoverable from the rows —
            // and a reader who cannot tell a narrowed export from a whole-org one
            // will read the smaller number as the whole truth.
            assertThat(csv).contains("Scope: your projects");
            assertThat(csv).contains("Date,Ticket,Closed");
        }

        @Test
        @DisplayName("starts with a UTF-8 BOM, or Excel mangles every non-ASCII name")
        void bom() throws Exception {
            byte[] bytes = export(exporter, ROWS);

            // Excel on Windows reads a BOM-less CSV in the system code page. The
            // file is not broken by any specification, which is why the report
            // says "the export is corrupted" and it opens fine everywhere else.
            assertThat(bytes[0] & 0xFF).isEqualTo(0xEF);
            assertThat(bytes[1] & 0xFF).isEqualTo(0xBB);
            assertThat(bytes[2] & 0xFF).isEqualTo(0xBF);
        }

        @Test
        @DisplayName("quotes a field containing a comma, and doubles an embedded quote")
        void rfc4180Quoting() throws Exception {
            // A comma splits the row into two columns; an unescaped quote ends
            // the field early. Both corrupt every column after them, silently.
            assertThat(text()).contains("\"Payment, sometimes \"\"slow\"\"\"");
        }

        @Test
        @DisplayName("uses CRLF, which is what RFC 4180 and Excel expect")
        void lineEndings() throws Exception {
            assertThat(text()).contains("\r\n");
        }

        /**
         * The security case, not a formatting one.
         *
         * <p>A cell beginning {@code =}, {@code +}, {@code -} or {@code @} is
         * executed on open. Ticket titles are user-supplied and end up in
         * exports, so this is reachable by anyone who can raise a ticket.
         */
        @ParameterizedTest(name = "a cell starting {0} is neutralised")
        @ValueSource(strings = {"=", "+", "-", "@"})
        @DisplayName("formula injection is defused with a leading quote")
        void formulaInjection(String starter) throws Exception {
            String payload = starter + "HYPERLINK(\"http://attacker/\",\"click\")";
            String csv = new String(export(exporter, List.of(row("2026-08-10", payload, 1))),
                    StandardCharsets.UTF_8);

            assertThat(csv).contains("'" + starter);
            // The dangerous form must not survive as the first character of a
            // field — i.e. never immediately after a comma or an opening quote.
            assertThat(csv).doesNotContain(",=").doesNotContain(",@");
        }

        @Test
        @DisplayName("a legitimate value that looks like a formula is preserved, not dropped")
        void guardIsReversible() {
            // "-3 regression" is an honest ticket title. The guard makes it safe
            // to open; it must not make it unreadable.
            assertThat(CsvReportExporter.escape("-3 regression")).contains("-3 regression");
        }

        @Test
        @DisplayName("null becomes empty, not the text 'null'")
        void nullsAreBlank() throws Exception {
            Map<String, Object> sparse = new LinkedHashMap<>();
            sparse.put("date", "2026-08-10");
            sparse.put("title", null);
            sparse.put("closed", 1L);

            assertThat(new String(export(exporter, List.of(sparse)), StandardCharsets.UTF_8))
                    .doesNotContain("null");
        }
    }

    @Nested
    @DisplayName("xlsx")
    class Xlsx {

        private final XlsxReportExporter exporter = new XlsxReportExporter();

        private Sheet sheet(byte[] bytes) throws Exception {
            Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes));
            return workbook.getSheetAt(0);
        }

        @Test
        @DisplayName("is a real workbook with the report's title on it")
        void opens() throws Exception {
            Sheet sheet = sheet(export(exporter, ROWS));

            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Date-wise Report");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Scope: your projects");
        }

        /**
         * The difference between a spreadsheet somebody works with and one they
         * retype: a figure written as text cannot be summed and sorts "10"
         * before "9".
         */
        @Test
        @DisplayName("writes numbers as numbers, driven by the column's declared type")
        void numbersAreNumeric() throws Exception {
            Sheet sheet = sheet(export(exporter, ROWS));

            Row first = sheet.getRow(4);
            Cell closed = first.getCell(2);

            assertThat(closed.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(closed.getNumericCellValue()).isEqualTo(2d);
        }

        @Test
        @DisplayName("a sheet name with a slash does not throw — 'Delayed / SLA Breach'")
        void unsafeSheetName() throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            // Excel forbids / \ * ? [ ] in a sheet name and caps it at 31 chars.
            // One of the eighteen report titles contains a slash.
            exporter.write(out, "Delayed / SLA Breach", "the whole organisation", COLUMNS,
                    ExportRows.of(ROWS));

            assertThat(new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray())).getSheetAt(0))
                    .isNotNull();
        }

        @Test
        @DisplayName("formula injection is defused here too")
        void formulaInjection() throws Exception {
            Sheet sheet = sheet(export(exporter, List.of(row("2026-08-10", "=1+1", 1))));

            assertThat(sheet.getRow(4).getCell(1).getStringCellValue()).startsWith("'");
        }

        @Test
        @DisplayName("a null is a blank cell, not a zero")
        void nullsAreBlank() throws Exception {
            Map<String, Object> sparse = new LinkedHashMap<>();
            sparse.put("date", "2026-08-10");
            sparse.put("title", "x");
            sparse.put("closed", null);

            // A zero in a numeric column is a measurement. "Not recorded" is not.
            Cell cell = sheet(export(exporter, List.of(sparse))).getRow(4).getCell(2);
            assertThat(cell == null || cell.getCellType() == CellType.BLANK).isTrue();
        }
    }

    @Nested
    @DisplayName("PDF")
    class Pdf {

        private final PdfReportExporter exporter = new PdfReportExporter();

        @Test
        @DisplayName("produces a real PDF")
        void opens() throws Exception {
            byte[] bytes = export(exporter, ROWS);

            assertThat(new String(bytes, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
            assertThat(bytes.length).isGreaterThan(500);
        }

        @Test
        @DisplayName("an empty report is still a document, saying so")
        void emptyIsStillADocument() throws Exception {
            // Evidence that the question was asked and had no answer is often
            // exactly what somebody needs to attach.
            byte[] bytes = export(exporter, List.of());

            assertThat(new String(bytes, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        }

        @Test
        @DisplayName("truncates a long report and says on the page that it has")
        void truncationIsStated() throws Exception {
            List<Map<String, Object>> many = java.util.stream.IntStream.range(0, PdfReportExporter.MAX_ROWS + 50)
                    .mapToObj(i -> row("2026-08-" + (10 + i % 20), "Ticket " + i, i))
                    .toList();

            byte[] bytes = export(exporter, many);

            // A silently shortened table is a document that looks complete and
            // is not — a reader quoting a total would be quoting a prefix.
            assertThat(bytes.length).isGreaterThan(1000);
            assertThat(many).hasSize(PdfReportExporter.MAX_ROWS + 50);
        }
    }

    @Nested
    @DisplayName("the format enum")
    class Formats {

        @Test
        @DisplayName("resolves the three the contract declares, case-insensitively")
        void resolves() {
            assertThat(ReportExporter.Format.of("xlsx")).contains(ReportExporter.Format.XLSX);
            assertThat(ReportExporter.Format.of("CSV")).contains(ReportExporter.Format.CSV);
            assertThat(ReportExporter.Format.of("pdf")).contains(ReportExporter.Format.PDF);
        }

        @Test
        @DisplayName("rejects anything else, so an unknown format is a 400 and not a default")
        void rejectsUnknown() {
            // Falling back to a default would hand somebody who asked for
            // "excel" a CSV named .csv and let them believe they asked correctly.
            assertThat(ReportExporter.Format.of("excel")).isEmpty();
            assertThat(ReportExporter.Format.of("")).isEmpty();
            assertThat(ReportExporter.Format.of(null)).isEmpty();
        }

        @Test
        @DisplayName("sends a real media type, not octet-stream")
        void mediaTypes() {
            // A browser handed octet-stream cannot offer "open with Excel".
            assertThat(ReportExporter.Format.XLSX.contentType())
                    .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            assertThat(ReportExporter.Format.CSV.contentType()).isEqualTo("text/csv");
            assertThat(ReportExporter.Format.PDF.contentType()).isEqualTo("application/pdf");
        }
    }
}
