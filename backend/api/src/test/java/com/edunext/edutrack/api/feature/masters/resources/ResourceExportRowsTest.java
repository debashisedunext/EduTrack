package com.edunext.edutrack.api.feature.masters.resources;

import com.edunext.edutrack.api.feature.reports.export.ExportDelivery;
import com.edunext.edutrack.api.feature.reports.export.ExportRows;
import com.edunext.edutrack.api.feature.reports.export.ReportExporter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * B-062 · the S-07 export, through the engine that now writes it.
 *
 * <h2>Ported from {@code ResourceExportWriterTest}, not replaced by it</h2>
 *
 * <p>That file asserted the byte-order mark, RFC-4180 quoting, CRLF endings, the
 * SXSSF spill past the window and the formula-injection guard, against a writer
 * this task deleted. Every one of those is now
 * {@code feature/reports/export}'s behaviour and is tested there — and deleting
 * these along with the writer would still have been wrong. The guarantee people
 * rely on is not "{@code CsvReportExporter} escapes a leading equals"; it is
 * "<em>the resource export</em> cannot be made to run a formula in somebody's
 * spreadsheet", and only a test that goes through this path says that.
 *
 * <p>So the assertions survive and the subject changed: they run against the
 * real exporters, driven exactly as {@code ResourceController} drives them.
 * A future refactor that routes this export somewhere unguarded fails here.
 *
 * <p>Reads the xlsx back with the DOM reader rather than asserting on bytes: a
 * file POI cannot reopen is a file Excel cannot open, and that is the only
 * property of a spreadsheet that actually matters here.
 */
class ResourceExportRowsTest {

    /**
     * The engine writes a title, a scope line and a blank before the header, so
     * the header is row 3 and data starts at row 4.
     *
     * <p>B-010's writer put the header on row 0 and had nowhere to say what the
     * file was. That block is the reason to consolidate rather than a cost of
     * it — see {@link ResourceExportRows#describe}.
     */
    private static final int HEADER_ROW = 3;
    private static final int FIRST_DATA_ROW = 4;

    private ResourceService service;
    private ExportDelivery delivery;

    @BeforeEach
    void setUp() {
        service = mock(ResourceService.class);
        // The real exporters, wired as Spring wires them. A mock here would let
        // this whole file pass while the export wrote nothing at all.
        delivery = new ExportDelivery(realExporters());
    }

    // ------------------------------------------------------------------
    // the mapping — this class's own subject
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("rows")
    class Rows {

        @Test
        @DisplayName("every declared column has a key in the row, and no row has a key with no column")
        void columnsAndKeysAgree() {
            // The engine reads a row *by column key*. A column whose key is not
            // in the row is a silently blank column; a key with no column is a
            // value that never reaches the file. Neither fails anywhere else.
            Map<String, Object> row = ResourceExportRows.asRow(ravi());

            assertThat(ResourceExportRows.COLUMNS)
                    .allSatisfy(column -> assertThat(row).containsKey(column.key()));
            assertThat(row.keySet())
                    .containsExactlyInAnyOrderElementsOf(
                            ResourceExportRows.COLUMNS.stream().map(c -> c.key()).toList());
        }

        @Test
        @DisplayName("open tickets is a number, so it is a number in both formats rather than one of each")
        void openTicketsIsNumeric() {
            // The two hand-written writers disagreed about this column: xlsx
            // wrote setCellValue(int) and CSV wrote String.valueOf. One
            // declaration now decides both.
            assertThat(ResourceExportRows.asRow(ravi()).get("openTicketCount"))
                    .isInstanceOf(Integer.class)
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("a resource with no open tickets records zero, which is a measurement")
        void zeroRatherThanNull() {
            assertThat(ResourceExportRows.asRow(neha()).get("openTicketCount")).isEqualTo(0);
        }

        @Test
        @DisplayName("never-logged-in is null rather than empty, so a spreadsheet filter can find it")
        void nullLastLoginIsNull() {
            // ISBLANK and "(Blanks)" tell a null cell from an empty string, and
            // "has never signed in" is exactly the row somebody filters for here.
            assertThat(ResourceExportRows.asRow(neha()).get("lastLoginAt")).isNull();
        }

        @Test
        @DisplayName("projects are names, joined — not ids")
        void projectsAsNames() {
            assertThat(ResourceExportRows.asRow(ravi()).get("projects")).isEqualTo("CRM Revamp, Payments");
        }

        @Test
        @DisplayName("status is the word, because TRUE/FALSE is ambiguous once the header scrolls off")
        void statusIsAWord() {
            assertThat(ResourceExportRows.asRow(neha()).get("status")).isEqualTo("Inactive");
            assertThat(ResourceExportRows.asRow(ravi()).get("status")).isEqualTo("Active");
        }

        @Test
        @DisplayName("the row is not pre-escaped — neutralising is the format's job, applied once")
        void noDoubleGuard() {
            // Escaping here as well would reach the file as ''-Ops.
            assertThat(ResourceExportRows.asRow(withDepartment("-Ops")).get("department"))
                    .isEqualTo("-Ops");
        }
    }

    @Nested
    @DisplayName("the scope line")
    class Scope {

        @Test
        @DisplayName("an unfiltered export says so rather than leaving the line empty")
        void unfiltered() {
            assertThat(ResourceExportRows.describe(ResourceFilter.NONE)).isEqualTo("every resource");
        }

        @Test
        @DisplayName("every filter the grid offers reaches the file")
        void everyFilter() {
            assertThat(ResourceExportRows.describe(new ResourceFilter("ravi", "QA", 7L, 9L, true)))
                    .contains("ravi")
                    .contains("role QA")
                    .contains("project 7")
                    .contains("reporting to user 9")
                    .contains("active only");
        }

        @Test
        @DisplayName("inactive-only is distinguishable from unfiltered, which is the case that misleads")
        void inactiveOnly() {
            // null means "both" for this field. A describer that printed nothing
            // for false would make the deactivated-only extract look complete.
            assertThat(ResourceExportRows.describe(new ResourceFilter(null, null, null, null, false)))
                    .isEqualTo("inactive only");
        }
    }

    // ------------------------------------------------------------------
    // xlsx — ported from ResourceExportWriterTest
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("xlsx")
    class Xlsx {

        @Test
        @DisplayName("writes a header row and one row per resource, in S-07 order")
        void writesHeaderAndRows() throws IOException {
            streaming(ravi(), neha());

            List<List<String>> sheet = readXlsx(write(ReportExporter.Format.XLSX));

            assertThat(sheet.get(HEADER_ROW))
                    .startsWith("Emp Code", "Name", "Username", "Email", "Role");
            assertThat(sheet.get(FIRST_DATA_ROW))
                    .containsSubsequence("EMP001", "Ravi Kumar", "ravi.kumar");
            assertThat(sheet).hasSize(FIRST_DATA_ROW + 2);
        }

        @Test
        @DisplayName("the file says what it is and what it was filtered to")
        void carriesItsProvenance() throws IOException {
            streaming(ravi());

            List<List<String>> sheet = readXlsx(write(ReportExporter.Format.XLSX,
                    new ResourceFilter(null, "QA", null, null, null)));

            assertThat(sheet.getFirst().getFirst()).isEqualTo("Resources");
            assertThat(sheet.get(1).getFirst()).isEqualTo("Scope: role QA");
        }

        @Test
        @DisplayName("renders projects as names, joined, not as ids")
        void projectsAsNames() throws IOException {
            streaming(ravi());

            assertThat(readXlsx(write(ReportExporter.Format.XLSX)).get(FIRST_DATA_ROW))
                    .contains("CRM Revamp, Payments");
        }

        @Test
        @DisplayName("status is the word, and a null last login is blank rather than 'null'")
        void statusAndNullLastLogin() throws IOException {
            streaming(neha());

            List<String> row = readXlsx(write(ReportExporter.Format.XLSX)).get(FIRST_DATA_ROW);

            assertThat(row).contains("Inactive");
            assertThat(row.getLast()).isEmpty();
        }

        @Test
        @DisplayName("an empty result still produces a readable file with its header")
        void emptyExportIsStillAWorkbook() throws IOException {
            streaming();

            // Header block only — evidence the question was asked and had no
            // answer, which is often exactly what somebody needs to attach.
            assertThat(readXlsx(write(ReportExporter.Format.XLSX))).hasSize(HEADER_ROW + 1);
        }

        @Test
        @DisplayName("survives more rows than the in-memory window, which is the point of SXSSF")
        void spillsBeyondTheWindow() throws IOException {
            // 250 rows against a 100-row window: everything below row 150 has
            // been flushed to a temp file by the time the workbook is written.
            // This is also what would break if the freeze pane were set at the
            // end rather than while row 0 is still resident.
            List<ResourceDtos.Resource> many = new ArrayList<>();
            for (int i = 0; i < 250; i++) {
                many.add(person(i, "Person %03d".formatted(i), true));
            }
            streaming(many.toArray(ResourceDtos.Resource[]::new));

            assertThat(readXlsx(write(ReportExporter.Format.XLSX))).hasSize(FIRST_DATA_ROW + 250);
        }

        @Test
        @DisplayName("the header stays visible when the sheet is scrolled")
        void headerIsFrozen() throws IOException {
            // Carried over from the deleted writer. Consolidation must not
            // quietly drop a property the file already had.
            streaming(ravi());

            try (XSSFWorkbook workbook =
                         new XSSFWorkbook(new ByteArrayInputStream(write(ReportExporter.Format.XLSX)))) {
                assertThat(workbook.getSheetAt(0).getPaneInformation()).isNotNull();
            }
        }
    }

    // ------------------------------------------------------------------
    // csv — ported from ResourceExportWriterTest
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("csv")
    class Csv {

        @Test
        @DisplayName("opens as UTF-8 in Excel, because it leads with a byte-order mark")
        void leadsWithABom() throws IOException {
            streaming(ravi());

            assertThat(write(ReportExporter.Format.CSV))
                    .startsWith(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        }

        @Test
        @DisplayName("doubles an embedded quote, per RFC 4180")
        void quoting() throws IOException {
            streaming(person(1, "Priya \"Pri\" Sharma", true));

            assertThat(csvText()).contains("\"Priya \"\"Pri\"\" Sharma\"");
        }

        @Test
        @DisplayName("a comma inside a name does not become a new column")
        void embeddedComma() throws IOException {
            streaming(person(1, "Sharma, Priya", true));

            List<String> cells = splitQuoted(csvText().lines().toList().get(FIRST_DATA_ROW));

            assertThat(cells.get(1)).isEqualTo("Sharma, Priya");
        }

        @Test
        @DisplayName("rows end CRLF, per RFC 4180")
        void crlfLineEndings() throws IOException {
            streaming(ravi());

            assertThat(csvText()).contains("\r\n");
        }

        @Test
        @DisplayName("the header labels are the ones S-07 has always shipped")
        void headerLabelsUnchanged() throws IOException {
            // People reconcile this file against a payroll extract by column
            // heading. A heading that moved because the writer behind it changed
            // is a broken reconciliation with no error message.
            streaming(ravi());

            assertThat(csvText().lines().toList().get(HEADER_ROW))
                    .isEqualTo("Emp Code,Name,Username,Email,Role,Department,Designation,"
                            + "Reporting Manager,Projects,Status,Open Tickets,Last Login (UTC)");
        }

        private String csvText() throws IOException {
            return new String(write(ReportExporter.Format.CSV), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------
    // formula injection — ported, and the reason the port matters
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("spreadsheet formula injection")
    class FormulaInjection {

        @ParameterizedTest
        @ValueSource(strings = {"=1+1", "+44 team", "-Ops", "@SUM(A1)"})
        @DisplayName("a field that would be read as a formula is prefixed into text")
        void neutralisedInCsv(String department) throws IOException {
            // Every one of these came out of a form somebody filled in. The
            // middle two are not attacks at all — a phone extension and a
            // department name — and Excel evaluates them just the same.
            streaming(withDepartment(department));

            assertThat(new String(write(ReportExporter.Format.CSV), StandardCharsets.UTF_8))
                    .contains("'" + department);
        }

        @Test
        @DisplayName("and in xlsx, where the cell would otherwise evaluate on open")
        void neutralisedInXlsx() throws IOException {
            streaming(withDepartment("=1+1"));

            assertThat(readXlsx(write(ReportExporter.Format.XLSX)).get(FIRST_DATA_ROW))
                    .contains("'=1+1");
        }
    }

    // ------------------------------------------------------------------
    // driving the export exactly as the controller does
    // ------------------------------------------------------------------

    private byte[] write(ReportExporter.Format format) throws IOException {
        return write(format, ResourceFilter.NONE);
    }

    private byte[] write(ReportExporter.Format format, ResourceFilter filter) throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        delivery.writeTo(response, format, ResourceExportRows.FILENAME_STEM,
                ResourceExportRows.TITLE, ResourceExportRows.describe(filter),
                ResourceExportRows.COLUMNS, ResourceExportRows.of(service, filter));
        return response.getContentAsByteArray();
    }

    /**
     * The real exporters, constructed the way the container does.
     *
     * <p>Reflection because they are package-private {@code @Component}s in
     * another package — which is correct, and is what the public
     * {@link ReportExporter} interface exists for. A test-only public
     * constructor would be API surface added for a test.
     */
    private static List<ReportExporter> realExporters() {
        return List.of(instantiate("CsvReportExporter"), instantiate("XlsxReportExporter"));
    }

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

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** Makes {@code streamAll} hand the source one batch containing these resources. */
    private void streaming(ResourceDtos.Resource... resources) {
        doAnswer(invocation -> {
            java.util.function.Consumer<List<ResourceDtos.Resource>> sink = invocation.getArgument(1);
            if (resources.length > 0) {
                sink.accept(List.of(resources));
            }
            return null;
        }).when(service).streamAll(any(), any());
    }

    private static ResourceDtos.Resource ravi() {
        return new ResourceDtos.Resource(
                1L, "Ravi Kumar", "DEVELOPER", "ravi.kumar", "ravi.kumar@edunext.test", "EMP001",
                "Engineering", "Senior Engineer",
                new ResourceDtos.UserRef(9L, "Meera Iyer", "PM"),
                List.of(1L, 2L),
                List.of(new ResourceDtos.ProjectRef(1L, "CRM", "CRM Revamp", "#4F46E5"),
                        new ResourceDtos.ProjectRef(2L, "PAY", "Payments", "#10B981")),
                true, 3, Instant.parse("2026-08-10T06:30:00Z"), Instant.parse("2025-04-01T00:00:00Z"));
    }

    /** Inactive, never logged in, no manager, no projects — every null path at once. */
    private static ResourceDtos.Resource neha() {
        return new ResourceDtos.Resource(
                2L, "Neha Singh", "QA", "neha.singh", "neha.singh@edunext.test", "EMP002",
                null, null, null, List.of(), List.of(), false, 0, null,
                Instant.parse("2025-06-01T00:00:00Z"));
    }

    private static ResourceDtos.Resource person(int id, String name, boolean isActive) {
        return new ResourceDtos.Resource(
                id, name, "DEVELOPER", "user" + id, "user" + id + "@edunext.test",
                "EMP%03d".formatted(id), "Engineering", "Engineer", null, List.of(), List.of(),
                isActive, 0, Instant.parse("2026-08-01T09:00:00Z"),
                Instant.parse("2025-01-01T00:00:00Z"));
    }

    private static ResourceDtos.Resource withDepartment(String department) {
        return new ResourceDtos.Resource(
                1L, "Ravi Kumar", "DEVELOPER", "ravi.kumar", "ravi.kumar@edunext.test", "EMP001",
                department, "Engineer", null, List.of(), List.of(), true, 0, null,
                Instant.parse("2025-01-01T00:00:00Z"));
    }

    // ------------------------------------------------------------------
    // readers
    // ------------------------------------------------------------------

    /**
     * Every cell as text, indexed by the sheet's own row numbers.
     *
     * <p>Padded rather than compacted, because the engine leaves a genuinely
     * empty row between the scope line and the header and POI's iterator skips
     * rows that were never created. Compacting would shift every index by one
     * and make {@link #HEADER_ROW} a lie that happens to work.
     */
    private static List<List<String>> readXlsx(byte[] bytes) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            List<List<String>> rows = new ArrayList<>();
            for (Row row : sheet) {
                while (rows.size() < row.getRowNum()) {
                    rows.add(List.of());
                }
                List<String> cells = new ArrayList<>();
                for (int i = 0; i < ResourceExportRows.COLUMNS.size(); i++) {
                    cells.add(row.getCell(i) == null ? "" : cellText(row, i));
                }
                rows.add(cells);
            }
            return rows;
        }
    }

    private static String cellText(Row row, int column) {
        return switch (row.getCell(column).getCellType()) {
            case NUMERIC -> String.valueOf((long) row.getCell(column).getNumericCellValue());
            case BLANK -> "";
            default -> row.getCell(column).getStringCellValue();
        };
    }

    /** A minimal RFC 4180 reader — enough to prove the writer's quoting holds. */
    private static List<String> splitQuoted(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString());
        return cells;
    }
}
