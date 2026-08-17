package com.edunext.edutrack.api.feature.imports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-032 · the CSV half of step 2.
 *
 * <p>Every case here is one this reader was written by hand to get right, and
 * every one of them is a file a user really uploads: a BOM Excel wrote and does
 * not mention, an address with a comma in it, a note with a line break in it, a
 * trailing blank row nobody can see.
 */
class CsvSheetReaderTest {

    private final CsvSheetReader reader = new CsvSheetReader(new ImportUploadLimits(5_242_880, 5_000, 200));

    @Test
    void readsHeadingsAndRows() {
        ParsedSheet sheet = read("clients.csv", """
                Client Code,Name,City
                ACME,Acme Corporation,Mumbai
                NORTHWIND,Northwind Traders,Pune
                """);

        assertThat(sheet.headers()).containsExactly("Client Code", "Name", "City");
        assertThat(sheet.rows()).hasSize(2);
        assertThat(sheet.rows().getFirst().cells())
                .containsEntry("Client Code", "ACME")
                .containsEntry("City", "Mumbai");
    }

    /**
     * The sheet is named after the file, because a CSV has none of its own and
     * "Sheet1" would be a label with no counterpart in anything the user
     * uploaded.
     */
    @Test
    void reportsOneSheetNamedAfterTheFile() {
        ParsedSheet sheet = read("april-clients.csv", "Client Code\nACME\n");

        assertThat(sheet.sheets()).containsExactly("april-clients");
        assertThat(sheet.sheet()).isEqualTo("april-clients");
    }

    @Test
    @DisplayName("asking for a sheet a CSV does not have is refused, and names the one it does")
    void refusesAnUnknownSheet() {
        assertThatThrownBy(() -> reader.read("clients.csv", bytes("Client Code\nACME\n"), "Sheet2"))
                .isInstanceOf(UnreadableImportFileException.class)
                .hasMessageContaining("no sheet called 'Sheet2'")
                .extracting(e -> ((UnreadableImportFileException) e).sheets())
                .isEqualTo(List.of("clients"));
    }

    /**
     * Excel writes a UTF-8 BOM and says nothing about it. Left in place it
     * becomes part of the first heading, so {@code Client Code} stops matching
     * {@code Client Code} — and the user is shown a mapping screen where the
     * first column mysteriously did not auto-match, with nothing visibly wrong.
     */
    @Test
    void stripsTheByteOrderMarkExcelWrites() {
        ParsedSheet sheet = read("clients.csv", "﻿Client Code,Name\nACME,Acme\n");

        assertThat(sheet.headers()).containsExactly("Client Code", "Name");
    }

    @Test
    @DisplayName("a quoted field keeps its commas")
    void quotedFieldsKeepDelimiters() {
        ParsedSheet sheet = read("clients.csv",
                "Client Code,Address Line 1\nACME,\"14 Marine Drive, Nariman Point\"\n");

        assertThat(sheet.rows().getFirst().cells())
                .containsEntry("Address Line 1", "14 Marine Drive, Nariman Point");
    }

    @Test
    @DisplayName("a doubled quote inside a quoted field is one quote")
    void doubledQuotesCollapse() {
        ParsedSheet sheet = read("clients.csv", "Name\n\"The \"\"Acme\"\" Group\"\n");

        assertThat(sheet.rows().getFirst().cells()).containsEntry("Name", "The \"Acme\" Group");
    }

    /**
     * The case that rules out splitting on newlines first, which is the
     * implementation everyone reaches for. A Notes or Address column is exactly
     * where a line break turns up, and the split version would silently make one
     * row into two malformed ones.
     */
    @Test
    @DisplayName("a newline inside a quoted field does not end the record")
    void quotedNewlinesStayInTheField() {
        ParsedSheet sheet = read("clients.csv",
                "Client Code,Notes\nACME,\"Renewal due Q1\nchase the PO\"\nZENITH,fine\n");

        assertThat(sheet.rows()).hasSize(2);
        assertThat(sheet.rows().getFirst().cells())
                .containsEntry("Notes", "Renewal due Q1\nchase the PO");
        assertThat(sheet.rows().get(1).cells()).containsEntry("Client Code", "ZENITH");
    }

    @Test
    void readsCrlfAndBareCrTerminators() {
        assertThat(read("a.csv", "Client Code\r\nACME\r\n").rows()).hasSize(1);
        assertThat(read("b.csv", "Client Code\rACME\r").rows()).hasSize(1);
    }

    @Test
    void readsALastRecordWithNoTrailingNewline() {
        assertThat(read("clients.csv", "Client Code\nACME").rows()).hasSize(1);
    }

    /**
     * <b>The reason {@link StagedRow} carries a number at all.</b> A blank row in
     * the middle of a file is dropped, so the row after it is the fourth record
     * and the third stored row — and step 4 must quote the number the user will
     * find in their own file, not its position in a list they cannot see.
     */
    @Test
    @DisplayName("blank rows are dropped and the numbers stay true to the file")
    void blankRowsDoNotShiftTheNumbering() {
        ParsedSheet sheet = read("clients.csv", """
                Client Code
                ACME

                ZENITH
                """);

        assertThat(sheet.rows()).hasSize(2);
        assertThat(sheet.rows().getFirst().number()).isEqualTo(2);
        assertThat(sheet.rows().get(1).number()).isEqualTo(4);
    }

    /**
     * Both columns survive. Dropping one would take a column of the user's file
     * out of the mapping screen entirely, with nothing anywhere saying so.
     */
    @Test
    @DisplayName("a repeated heading is suffixed rather than one of the columns disappearing")
    void repeatedHeadingsAreSuffixed() {
        ParsedSheet sheet = read("clients.csv",
                "Email,Email\naccounts@acme.example,support@acme.example\n");

        assertThat(sheet.headers()).containsExactly("Email", "Email (2)");
        assertThat(sheet.rows().getFirst().cells())
                .containsEntry("Email", "accounts@acme.example")
                .containsEntry("Email (2)", "support@acme.example");
    }

    /** A nameless column still has to be addressable, so it gets its spreadsheet letter. */
    @Test
    void anEmptyHeadingBetweenRealOnesIsNamedForItsColumn() {
        ParsedSheet sheet = read("clients.csv", "Client Code,,Name\nACME,x,Acme\n");

        assertThat(sheet.headers()).containsExactly("Client Code", "Column B", "Name");
    }

    @Test
    void refusesAFileWithNoHeadingRow() {
        assertThatThrownBy(() -> read("empty.csv", "\n\n"))
                .isInstanceOf(UnreadableImportFileException.class)
                .hasMessageContaining("no heading row");
    }

    /**
     * Blueprint §4B.3's 5,000. Asserted against a small ceiling because the
     * behaviour is the limit being enforced, not the number.
     */
    @Test
    @DisplayName("past the row limit it refuses, naming the limit rather than the file")
    void refusesPastTheRowLimit() {
        CsvSheetReader small = new CsvSheetReader(new ImportUploadLimits(5_242_880, 2, 200));

        assertThatThrownBy(() -> small.read("clients.csv",
                bytes("Client Code\nA\nB\nC\n"), null))
                .isInstanceOf(ImportLimitExceededException.class)
                .hasMessageContaining("more than 2 rows");
    }

    @Test
    void refusesPastTheColumnLimit() {
        CsvSheetReader narrow = new CsvSheetReader(new ImportUploadLimits(5_242_880, 5_000, 2));

        assertThatThrownBy(() -> narrow.read("clients.csv", bytes("A,B,C\n1,2,3\n"), null))
                .isInstanceOf(ImportLimitExceededException.class)
                .hasMessageContaining("3 columns");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private ParsedSheet read(String fileName, String content) {
        return reader.read(fileName, bytes(content), null);
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }
}
