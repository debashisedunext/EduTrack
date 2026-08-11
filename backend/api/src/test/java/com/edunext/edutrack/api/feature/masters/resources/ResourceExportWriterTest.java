package com.edunext.edutrack.api.feature.masters.resources;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * B-010 · the export, in both formats.
 *
 * <p>Reads the xlsx back with the DOM reader rather than asserting on bytes:
 * a file that POI cannot reopen is a file Excel cannot open, and that is the
 * only property of a spreadsheet that actually matters here.
 */
class ResourceExportWriterTest {

    private ResourceService service;
    private ResourceExportWriter writer;

    @BeforeEach
    void setUp() {
        service = mock(ResourceService.class);
        writer = new ResourceExportWriter(service);
    }

    // ------------------------------------------------------------------
    // xlsx
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("xlsx")
    class Xlsx {

        @Test
        @DisplayName("writes a header row and one row per resource, in S-07 order")
        void writesHeaderAndRows() throws IOException {
            streaming(ravi(), neha());

            List<List<String>> sheet = readXlsx(writeXlsx());

            assertThat(sheet.getFirst()).startsWith("Emp Code", "Name", "Username", "Email", "Role");
            assertThat(sheet).hasSize(3);
            assertThat(sheet.get(1)).containsSubsequence("EMP001", "Ravi Kumar", "ravi.kumar");
        }

        @Test
        @DisplayName("renders projects as names, joined, not as ids")
        void projectsAsNames() throws IOException {
            streaming(ravi());

            assertThat(readXlsx(writeXlsx()).get(1)).contains("CRM Revamp, Payments");
        }

        @Test
        @DisplayName("status is the word, and a null last login is blank rather than 'null'")
        void statusAndNullLastLogin() throws IOException {
            streaming(neha());

            List<String> row = readXlsx(writeXlsx()).get(1);

            assertThat(row).contains("Inactive");
            assertThat(row.getLast()).isEmpty();
        }

        @Test
        @DisplayName("an empty result still produces a readable file with its header")
        void emptyExportIsStillAWorkbook() throws IOException {
            streaming();

            assertThat(readXlsx(writeXlsx())).hasSize(1);
        }

        @Test
        @DisplayName("survives more rows than the in-memory window, which is the point of SXSSF")
        void spillsBeyondTheWindow() throws IOException {
            // 250 rows against a 100-row window: everything below row 150 has
            // been flushed to a temp file by the time the workbook is written.
            List<ResourceDtos.Resource> many = new ArrayList<>();
            for (int i = 0; i < 250; i++) {
                many.add(person(i, "Person %03d".formatted(i), true));
            }
            streaming(many.toArray(ResourceDtos.Resource[]::new));

            assertThat(readXlsx(writeXlsx())).hasSize(251);
        }

        private byte[] writeXlsx() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writer.writeXlsx(ResourceFilter.NONE, out);
            return out.toByteArray();
        }
    }

    // ------------------------------------------------------------------
    // csv
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("csv")
    class Csv {

        @Test
        @DisplayName("opens as UTF-8 in Excel, because it leads with a byte-order mark")
        void leadsWithABom() throws IOException {
            streaming(ravi());

            assertThat(writeCsv()).startsWith(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        }

        @Test
        @DisplayName("quotes every field and doubles an embedded quote, per RFC 4180")
        void quoting() throws IOException {
            streaming(person(1, "Priya \"Pri\" Sharma", true));

            assertThat(csvText()).contains("\"Priya \"\"Pri\"\" Sharma\"");
        }

        @Test
        @DisplayName("a comma inside a name does not become a new column")
        void embeddedComma() throws IOException {
            streaming(person(1, "Sharma, Priya", true));

            List<String> cells = splitQuoted(csvText().lines().toList().get(1));

            assertThat(cells.get(1)).isEqualTo("Sharma, Priya");
        }

        @Test
        @DisplayName("rows end CRLF, per RFC 4180")
        void crlfLineEndings() throws IOException {
            streaming(ravi());

            assertThat(csvText()).contains("\r\n");
        }

        private byte[] writeCsv() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writer.writeCsv(ResourceFilter.NONE, out);
            return out.toByteArray();
        }

        private String csvText() throws IOException {
            return new String(writeCsv(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------
    // formula injection
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

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writer.writeCsv(ResourceFilter.NONE, out);

            assertThat(out.toString(StandardCharsets.UTF_8)).contains("\"'" + department + "\"");
        }

        @Test
        @DisplayName("and in xlsx, where the cell would otherwise evaluate on open")
        void neutralisedInXlsx() throws IOException {
            streaming(withDepartment("=1+1"));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writer.writeXlsx(ResourceFilter.NONE, out);

            assertThat(readXlsx(out.toByteArray()).get(1)).contains("'=1+1");
        }
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** Makes {@code streamAll} hand the writer one batch containing these resources. */
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
                id, name, "DEVELOPER", "user" + id, "user" + id + "@edunext.test", "EMP%03d".formatted(id),
                "Engineering", "Engineer", null, List.of(), List.of(), isActive, 0,
                Instant.parse("2026-08-01T09:00:00Z"), Instant.parse("2025-01-01T00:00:00Z"));
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

    /** Every cell as text, row by row. Blank trailing cells are preserved as "". */
    private static List<List<String>> readXlsx(byte[] bytes) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            List<List<String>> rows = new ArrayList<>();
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                for (int i = 0; i < 12; i++) {
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
