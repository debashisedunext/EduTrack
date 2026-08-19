package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.feature.reports.export.ExportDelivery;
import com.edunext.edutrack.api.feature.reports.export.ExportRows;
import com.edunext.edutrack.api.feature.reports.export.ReportExporter;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * B-062 · every report the catalogue declares is exportable, in every format the
 * route offers.
 *
 * <h2>What was missing</h2>
 *
 * <p>A-064 wrote the engine so that "a report added by A-066 is exportable the
 * day it lands without touching this package", and nothing anywhere asserted it.
 * {@code ReportExporterTest} exports one hand-written report; {@code ReportsIT}
 * runs the reports and never exports them. A nineteenth report — or a column
 * type a runner introduces — becomes exportable the day somebody clicks it,
 * which is a working day after it shipped.
 *
 * <p>So this drives the catalogue. Every descriptor, built or not, is exported
 * in all three formats against a synthetic row of its declared column types,
 * because what can be got wrong is the <em>shape</em> — a title Excel refuses as
 * a sheet name, a type no exporter handles, a report with no numeric column at
 * all — and not the figures, which are the runners' business and are tested
 * against MySQL where they belong.
 *
 * <h2>Unbuilt reports are included on purpose</h2>
 *
 * <p>Five of the eighteen have no runner yet. Their descriptors are what the
 * task that builds them starts from, and a title that breaks the xlsx writer is
 * better found now than by the person who has just finished the SQL.
 */
@DisplayName("export contract")
class ReportExportContractTest {

    /*
      The real exporters, by reflection, because they are package-private
      @Components in feature/reports/export and ReportExporter is the published
      surface — which is the arrangement that let feature/audit and
      feature/masters/resources reuse them at all. A test-only public constructor
      would be API added for a test; the catalogue this drives is package-private
      in *this* package, so the test cannot simply live next to them instead.
    */
    private static final ReportExporter CSV = instantiate("CsvReportExporter");
    private static final ReportExporter XLSX = instantiate("XlsxReportExporter");
    private static final ReportExporter PDF = instantiate("PdfReportExporter");

    private static final List<ReportExporter> ALL = List.of(CSV, XLSX, PDF);

    /**
     * What {@code PdfReportExporter.MAX_ROWS} is, restated rather than imported —
     * it is package-private over there. Any value above it exercises the same
     * branch, so this is a floor and not a copy that has to stay in step.
     */
    private static final int MORE_THAN_THE_PDF_PRINTS = 200;

    private static ReportExporter instantiate(String simpleName) {
        try {
            Class<?> type = Class.forName(
                    "com.edunext.edutrack.api.feature.reports.export." + simpleName);
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return (ReportExporter) constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("No " + simpleName + " to test against", e);
        }
    }

    /** Every descriptor the hub can show, paired with every format the route offers. */
    private static Stream<org.junit.jupiter.params.provider.Arguments> everyReportInEveryFormat() {
        List<org.junit.jupiter.params.provider.Arguments> cases = new ArrayList<>();
        for (ReportDtos.Descriptor descriptor : ReportCatalogue.declared()) {
            for (ReportExporter exporter : ALL) {
                cases.add(org.junit.jupiter.params.provider.Arguments.of(
                        descriptor.key(), descriptor.title(), exporter));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} as {2}")
    @MethodSource("everyReportInEveryFormat")
    @DisplayName("every declared report writes a file its own reader can open")
    void everyReportExports(String key, String title, ReportExporter exporter) throws Exception {
        byte[] file = write(exporter, title, typedColumns(), typedRows(3));

        assertThat(file).describedAs("%s as %s produced nothing", key, exporter.format()).isNotEmpty();
        assertThat(file).startsWith(magic(exporter.format()));
    }

    @ParameterizedTest(name = "{0} as {2}")
    @MethodSource("everyReportInEveryFormat")
    @DisplayName("and does so with no rows, which is a report somebody still needs to attach")
    void everyReportExportsEmpty(String key, String title, ReportExporter exporter) throws Exception {
        // An empty file is evidence the question was asked and had no answer.
        // The PDF takes a different branch entirely when there is nothing to
        // chart, and it is the branch nobody looks at.
        assertThat(write(exporter, title, typedColumns(), typedRows(0)))
                .describedAs("%s as %s produced nothing when empty", key, exporter.format())
                .isNotEmpty();
    }

    @Nested
    @DisplayName("column types")
    class ColumnTypes {

        @ParameterizedTest
        @EnumSource(ReportDtos.ColumnType.class)
        @DisplayName("no declared type makes an exporter throw")
        void everyTypeIsWritable(ReportDtos.ColumnType type) {
            // The tripwire for a type added by a future report. B-061 added one
            // and had to find, by reading, the two switch statements that needed
            // to know about it; a third exporter added later would not announce
            // itself at all.
            List<ReportDtos.Column> columns = List.of(new ReportDtos.Column("v", "Value", type));
            List<Map<String, Object>> rows = List.of(Map.of("v", 42), Map.of("v", "forty-two"));

            for (ReportExporter exporter : ALL) {
                assertThatCode(() -> write(exporter, "Types", columns, rows))
                        .describedAs("%s could not write a %s column", exporter.format(), type)
                        .doesNotThrowAnyException();
            }
        }

        @ParameterizedTest
        @EnumSource(ReportDtos.ColumnType.class)
        @DisplayName("and no declared type silently drops the value it was given")
        void everyTypeSurvivesToTheFile(ReportDtos.ColumnType type) throws Exception {
            // A type falling through an unhandled branch does not throw — it
            // writes a blank cell, which in a column of figures reads as zero.
            List<ReportDtos.Column> columns = List.of(new ReportDtos.Column("v", "Value", type));
            List<Map<String, Object>> rows = List.of(Map.of("v", 42));

            String csv = new String(write(CSV, "Types", columns, rows), StandardCharsets.UTF_8);

            assertThat(csv).describedAs("a %s column lost its value", type).contains("42");
        }

        @Test
        @DisplayName("a numeric type is a number in the spreadsheet, not text that cannot be summed")
        void numericTypesStayNumeric() throws Exception {
            // The reason ColumnType exists on the contract at all. A figure
            // written as text is left-aligned, cannot be summed, and sorts "10"
            // before "9" — the difference between a spreadsheet somebody works
            // with and one they retype.
            for (ReportDtos.ColumnType type : List.of(ReportDtos.ColumnType.NUMBER,
                    ReportDtos.ColumnType.PERCENT, ReportDtos.ColumnType.DURATION)) {

                byte[] file = write(XLSX, "Types",
                        List.of(new ReportDtos.Column("v", "Value", type)),
                        List.of(Map.of("v", 42)));

                try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
                    Sheet sheet = workbook.getSheetAt(0);
                    assertThat(sheet.getRow(4).getCell(0).getCellType())
                            .describedAs("%s reached the sheet as text", type)
                            .isEqualTo(CellType.NUMERIC);
                }
            }
        }
    }

    @Nested
    @DisplayName("titles")
    class Titles {

        @Test
        @DisplayName("every catalogue title survives becoming a sheet name")
        void everyTitleIsASafeSheetName() {
            // Excel forbids / \ * ? [ ] and caps a sheet name at 31 characters.
            // "Delayed / SLA Breach" is already one of the eighteen, and the next
            // report to contain a slash would throw on the export rather than on
            // the run — after its own tests were green.
            for (ReportDtos.Descriptor descriptor : ReportCatalogue.declared()) {
                assertThatCode(() -> write(XLSX, descriptor.title(), typedColumns(), typedRows(1)))
                        .describedAs("'%s' is not a usable sheet name", descriptor.title())
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("every catalogue key survives becoming a filename")
        void everyKeyIsASafeFilename() {
            for (ReportDtos.Descriptor descriptor : ReportCatalogue.declared()) {
                for (ReportExporter.Format format : ReportExporter.Format.values()) {
                    assertThat(ExportDelivery.filenameFor(descriptor.key(), format))
                            .describedAs("'%s' would break a shell script", descriptor.key())
                            .doesNotContain("/", "\\", " ", "\"")
                            .endsWith("." + format.wire());
                }
            }
        }
    }

    @Nested
    @DisplayName("truncation")
    class Truncation {

        @Test
        @DisplayName("the PDF says how many rows it left out, and the data formats leave none out")
        void pdfSaysWhatItTruncated() throws Exception {
            int rows = MORE_THAN_THE_PDF_PRINTS;

            // CSV is the one that can be counted without a PDF reader, and it is
            // the format the truncation notice sends people to.
            String csv = new String(write(CSV, "Big", typedColumns(), typedRows(rows)),
                    StandardCharsets.UTF_8);
            // 4 lines of header block, then one line per row.
            assertThat(csv.lines().count()).isEqualTo(4 + rows);

            assertThat(write(PDF, "Big", typedColumns(), typedRows(rows))).isNotEmpty();
        }

        @Test
        @DisplayName("a source is walked once, because a streaming one cannot be walked twice")
        void theSourceIsDrainedOnce() throws Exception {
            // The PDF buffers and counts; if it drained the source and then
            // re-read it for the chart, a cursor-backed export would render an
            // empty document with a correct row count above it.
            int[] passes = {0};
            ExportRows once = sink -> {
                passes[0]++;
                typedRows(3).forEach(sink);
            };

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PDF.write(out, "Once", "everything", typedColumns(), once);

            assertThat(passes[0]).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** One column of every declared type, so no report's shape is unrepresented. */
    private static List<ReportDtos.Column> typedColumns() {
        List<ReportDtos.Column> columns = new ArrayList<>();
        for (ReportDtos.ColumnType type : ReportDtos.ColumnType.values()) {
            columns.add(new ReportDtos.Column(type.name().toLowerCase(java.util.Locale.ROOT),
                    type.name().charAt(0) + type.name().substring(1).toLowerCase(java.util.Locale.ROOT),
                    type));
        }
        return columns;
    }

    private static List<Map<String, Object>> typedRows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (ReportDtos.Column column : typedColumns()) {
                row.put(column.key(), switch (column.type()) {
                    case STRING -> "row " + i;
                    case DATE -> "2026-08-%02d".formatted((i % 28) + 1);
                    default -> i;
                });
            }
            rows.add(row);
        }
        return rows;
    }

    private static byte[] write(ReportExporter exporter, String title,
                                List<ReportDtos.Column> columns,
                                List<Map<String, Object>> rows) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exporter.write(out, title, "the whole organisation", columns, ExportRows.of(rows));
        return out.toByteArray();
    }

    /** What the format's own reader looks for in the first bytes. */
    private static byte[] magic(ReportExporter.Format format) {
        return switch (format) {
            // A zip local-file header — an xlsx is a zip, and this is what POI
            // and Excel check before anything else.
            case XLSX -> new byte[] {0x50, 0x4B, 0x03, 0x04};
            case PDF -> "%PDF".getBytes(StandardCharsets.US_ASCII);
            // The byte-order mark, without which Excel reads the file in the
            // system code page and mangles every non-ASCII name in it.
            case CSV -> new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        };
    }
}
