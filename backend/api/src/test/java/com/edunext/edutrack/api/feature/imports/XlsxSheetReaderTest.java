package com.edunext.edutrack.api.feature.imports;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-032 · the workbook half of step 2, read back through the event API.
 *
 * <p>The fixtures are written with {@code XSSFWorkbook} — the DOM model this
 * reader exists to avoid. That is deliberate and is not a contradiction: the ban
 * is on the <em>production read path</em>, where the file is somebody else's and
 * the concurrency is real. A test that both wrote and read through the streaming
 * API would be asserting one implementation against itself; writing through the
 * ordinary user model and reading through the streaming one is what makes these
 * assertions mean something about real files.
 */
class XlsxSheetReaderTest {

    private final XlsxSheetReader reader =
            new XlsxSheetReader(new ImportUploadLimits(5_242_880, 5_000, 200));

    @Test
    void readsHeadingsAndRowsFromTheFirstSheet() {
        byte[] file = workbook(book -> {
            Sheet sheet = book.createSheet("Clients");
            write(sheet, 0, "Client Code", "Name");
            write(sheet, 1, "ACME", "Acme Corporation");
            write(sheet, 2, "NORTHWIND", "Northwind Traders");
        });

        ParsedSheet parsed = reader.read("clients.xlsx", file, null);

        assertThat(parsed.sheet()).isEqualTo("Clients");
        assertThat(parsed.headers()).containsExactly("Client Code", "Name");
        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().getFirst().cells()).containsEntry("Client Code", "ACME");
    }

    /**
     * §4B.3: "first sheet by default, sheet selector if the workbook has
     * several". Both halves in one assertion, because the selector is only
     * useful if the list it renders is complete.
     */
    @Test
    @DisplayName("every sheet is listed for the selector, and the first is read by default")
    void listsEverySheetAndDefaultsToTheFirst() {
        byte[] file = twoSheets();

        ParsedSheet parsed = reader.read("clients.xlsx", file, null);

        assertThat(parsed.sheets()).containsExactly("Clients", "Archive");
        assertThat(parsed.sheet()).isEqualTo("Clients");
        assertThat(parsed.rows().getFirst().cells()).containsEntry("Client Code", "ACME");
    }

    @Test
    void readsTheNamedSheetWhenTheSelectorChooses() {
        ParsedSheet parsed = reader.read("clients.xlsx", twoSheets(), "Archive");

        assertThat(parsed.sheet()).isEqualTo("Archive");
        assertThat(parsed.sheets()).containsExactly("Clients", "Archive");
        assertThat(parsed.rows().getFirst().cells()).containsEntry("Client Code", "OLDCO");
    }

    @Test
    @DisplayName("a sheet the workbook does not have is refused, listing the ones it does")
    void refusesAnUnknownSheet() {
        assertThatThrownBy(() -> reader.read("clients.xlsx", twoSheets(), "Sheet3"))
                .isInstanceOf(UnreadableImportFileException.class)
                .hasMessageContaining("no sheet called 'Sheet3'")
                .extracting(e -> ((UnreadableImportFileException) e).sheets())
                .isEqualTo(List.of("Clients", "Archive"));
    }

    /**
     * <b>The single most valuable assertion in this file.</b> A date typed into
     * Excel is formatted {@code dd/MM/yyyy} across most of the world, and without
     * the ISO formatter every row of a file with a Contract Start column is
     * rejected at step 4 — by a validator the user cannot see, over a value they
     * never typed, in cells that look perfectly correct in their spreadsheet.
     */
    @Test
    @DisplayName("a date cell reads as ISO whatever the sheet formats it as")
    void datesComeBackIso() {
        byte[] file = workbook(book -> {
            Sheet sheet = book.createSheet("Clients");
            write(sheet, 0, "Client Code", "Contract Start");

            CellStyle ddmmyyyy = book.createCellStyle();
            ddmmyyyy.setDataFormat(book.createDataFormat().getFormat("dd/MM/yyyy"));

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("ACME");
            Cell date = row.createCell(1);
            date.setCellValue(LocalDate.of(2026, 4, 1));
            date.setCellStyle(ddmmyyyy);
        });

        ParsedSheet parsed = reader.read("clients.xlsx", file, null);

        assertThat(parsed.rows().getFirst().cells()).containsEntry("Contract Start", "2026-04-01");
    }

    /** See {@link StagedRow} — this is what the row number is for. */
    @Test
    @DisplayName("a blank row is dropped and the rows after it keep their spreadsheet numbers")
    void blankRowsDoNotShiftTheNumbering() {
        byte[] file = workbook(book -> {
            Sheet sheet = book.createSheet("Clients");
            write(sheet, 0, "Client Code");
            write(sheet, 1, "ACME");
            sheet.createRow(2);                 // present, empty — Excel does this constantly
            write(sheet, 3, "ZENITH");
        });

        ParsedSheet parsed = reader.read("clients.xlsx", file, null);

        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().getFirst().number()).isEqualTo(2);
        assertThat(parsed.rows().get(1).number()).isEqualTo(4);
    }

    /**
     * The cap stops the parse rather than trimming the result, which is the whole
     * reason it is expressed as an exception from inside the content handler. It
     * cannot be asserted directly from out here — what is asserted is that a file
     * past the limit is refused rather than silently truncated, which is the
     * behaviour a user would otherwise never be told about.
     */
    @Test
    void refusesPastTheRowLimit() {
        XlsxSheetReader small = new XlsxSheetReader(new ImportUploadLimits(5_242_880, 3, 200));
        byte[] file = workbook(book -> {
            Sheet sheet = book.createSheet("Clients");
            write(sheet, 0, "Client Code");
            for (int row = 1; row <= 10; row++) {
                write(sheet, row, "CODE" + row);
            }
        });

        assertThatThrownBy(() -> small.read("clients.xlsx", file, null))
                .isInstanceOf(ImportLimitExceededException.class)
                .hasMessageContaining("more than 3 rows");
    }

    @Test
    void refusesPastTheColumnLimit() {
        XlsxSheetReader narrow = new XlsxSheetReader(new ImportUploadLimits(5_242_880, 5_000, 2));
        byte[] file = workbook(book -> write(book.createSheet("Clients"), 0, "A", "B", "C"));

        assertThatThrownBy(() -> narrow.read("clients.xlsx", file, null))
                .isInstanceOf(ImportLimitExceededException.class)
                .hasMessageContaining("3 columns");
    }

    @Test
    @DisplayName("a repeated heading is suffixed, so neither column disappears")
    void repeatedHeadingsAreSuffixed() {
        byte[] file = workbook(book -> {
            Sheet sheet = book.createSheet("Clients");
            write(sheet, 0, "Email", "Email");
            write(sheet, 1, "accounts@acme.example", "support@acme.example");
        });

        ParsedSheet parsed = reader.read("clients.xlsx", file, null);

        assertThat(parsed.headers()).containsExactly("Email", "Email (2)");
        assertThat(parsed.rows().getFirst().cells())
                .containsEntry("Email", "accounts@acme.example")
                .containsEntry("Email (2)", "support@acme.example");
    }

    @Test
    void refusesASheetWithNothingInIt() {
        byte[] file = workbook(book -> book.createSheet("Clients"));

        assertThatThrownBy(() -> reader.read("clients.xlsx", file, null))
                .isInstanceOf(UnreadableImportFileException.class)
                .hasMessageContaining("no heading row");
    }

    /**
     * A {@code .pdf} renamed, a truncated download, an encrypted book. The
     * message must be about the file rather than about POI's internals — a user
     * cannot act on "Package should contain a content type part".
     */
    @Test
    @DisplayName("content that is not a workbook is a readable refusal, not a stack trace")
    void refusesContentThatIsNotAWorkbook() {
        byte[] notAWorkbook = "%PDF-1.7 this is not a spreadsheet".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> reader.read("clients.xlsx", notAWorkbook, null))
                .isInstanceOf(UnreadableImportFileException.class)
                .hasMessageContaining("could not be read as a .xlsx")
                .hasMessageContaining("renamed");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static byte[] twoSheets() {
        return workbook(book -> {
            Sheet clients = book.createSheet("Clients");
            write(clients, 0, "Client Code", "Name");
            write(clients, 1, "ACME", "Acme Corporation");

            Sheet archive = book.createSheet("Archive");
            write(archive, 0, "Client Code", "Name");
            write(archive, 1, "OLDCO", "Oldco Limited");
        });
    }

    private static byte[] workbook(Consumer<XSSFWorkbook> build) {
        try (XSSFWorkbook book = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            build.accept(book);
            book.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("could not build the fixture workbook", e);
        }
    }

    private static void write(Sheet sheet, int rowIndex, String... values) {
        Row row = sheet.createRow(rowIndex);
        for (int column = 0; column < values.length; column++) {
            row.createCell(column).setCellValue(values[column]);
        }
    }
}
