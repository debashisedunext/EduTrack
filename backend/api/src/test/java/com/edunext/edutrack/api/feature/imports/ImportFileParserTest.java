package com.edunext.edutrack.api.feature.imports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** B-032 · which reader runs, and what happens when none does. */
class ImportFileParserTest {

    private final ImportUploadLimits limits = new ImportUploadLimits(5_242_880, 5_000, 200);
    private final ImportFileParser parser =
            new ImportFileParser(new XlsxSheetReader(limits), new CsvSheetReader(limits));

    @Test
    void routesCsvToTheCsvReader() {
        ParsedSheet parsed = parser.parse("clients.csv", csv(), null);

        assertThat(parsed.headers()).containsExactly("Client Code", "Name");
    }

    @Test
    @DisplayName("the extension is read from the last dot, not the first")
    void handlesADottedFileName() {
        ParsedSheet parsed = parser.parse("clients.2026-04.csv", csv(), null);

        assertThat(parsed.headers()).containsExactly("Client Code", "Name");
    }

    /**
     * The stated deviation from blueprint §4B.3, which lists {@code .xls}. See
     * {@link UnsupportedImportFileException} for the argument; what matters at
     * this level is that the refusal <b>names the fix</b>. "Unsupported file
     * type" alone would leave a user holding a file their own spreadsheet
     * application opens without complaint.
     */
    @Test
    @DisplayName("a legacy .xls is refused with the Save As instruction, not a bare rejection")
    void refusesLegacyXlsAndSaysWhatToDo() {
        assertThatThrownBy(() -> parser.parse("clients.xls", csv(), null))
                .isInstanceOf(UnsupportedImportFileException.class)
                .hasMessageContaining("Save As")
                .hasMessageContaining(".xlsx");
    }

    @Test
    void refusesATypeWithNoReader() {
        assertThatThrownBy(() -> parser.parse("clients.pdf", csv(), null))
                .isInstanceOf(UnsupportedImportFileException.class)
                .hasMessageContaining("'.pdf' files are not accepted");
    }

    @Test
    void refusesANameWithNoExtension() {
        assertThatThrownBy(() -> parser.parse("clients", csv(), null))
                .isInstanceOf(UnsupportedImportFileException.class)
                .hasMessageContaining("no extension");
    }

    /**
     * Routing is by extension and verification is by parsing — see
     * {@link ImportFileParser}. A CSV renamed {@code .xlsx} therefore reaches the
     * workbook reader and is refused there, which is the 422 rather than the 415:
     * the type is one we read, this file is not one of them.
     */
    @Test
    @DisplayName("a mislabelled file is caught by the parse, which is where the real check is")
    void aMislabelledFileFailsInTheReader() {
        assertThatThrownBy(() -> parser.parse("clients.xlsx", csv(), null))
                .isInstanceOf(UnreadableImportFileException.class);
    }

    private static byte[] csv() {
        return "Client Code,Name\nACME,Acme Corporation\n".getBytes(StandardCharsets.UTF_8);
    }
}
