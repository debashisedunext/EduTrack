package com.edunext.edutrack.api.feature.imports;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * B-032 · picks a {@link SheetReader} by extension, and refuses everything else.
 *
 * <p><b>By extension and not by sniffing the bytes</b>, which is the opposite of
 * the call §4B.4 makes for attachments — and the difference is what the check is
 * protecting. An attachment is stored and served back to other people, so its
 * declared type is a security claim and the extension proves nothing. This file
 * is parsed once, in this process, and thrown away; whichever reader is chosen,
 * a mismatch surfaces immediately as {@link UnreadableImportFileException}
 * because the content does not parse. The extension is a routing hint, and the
 * parse is the verification.
 *
 * <p>Adding {@code .xls} back — see {@link UnsupportedImportFileException} for
 * why it is out — is one entry in {@link #READERS} and one new
 * {@link SheetReader}. Nothing else in the package would know.
 */
@Component
class ImportFileParser {

    private final Map<String, SheetReader> readers;

    /** Named separately from {@link #readers} so the javadoc above can point at it. */
    private static final String XLSX = "xlsx";
    private static final String CSV = "csv";

    ImportFileParser(XlsxSheetReader xlsx, CsvSheetReader csv) {
        this.readers = Map.of(XLSX, xlsx, CSV, csv);
    }

    /**
     * @param requestedSheet which sheet to read, or {@code null} for the first
     * @throws UnsupportedImportFileException a type with no reader — 415
     * @throws UnreadableImportFileException  the right extension, unreadable content — 422
     * @throws ImportLimitExceededException   past the row or column ceiling — 413
     */
    ParsedSheet parse(String fileName, byte[] content, String requestedSheet) {
        String extension = extensionOf(fileName);
        SheetReader reader = readers.get(extension);
        if (reader == null) {
            throw "xls".equals(extension)
                    ? UnsupportedImportFileException.legacyXls()
                    : UnsupportedImportFileException.unknown(extension);
        }
        return reader.read(fileName, content, requestedSheet);
    }

    /**
     * Lowercased, without the dot, and empty when there is none.
     *
     * <p>Read from the last dot rather than the first, so
     * {@code clients.2026-04.xlsx} is a workbook and not an unknown type called
     * {@code 2026-04.xlsx}.
     */
    private static String extensionOf(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1
                ? ""
                : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
