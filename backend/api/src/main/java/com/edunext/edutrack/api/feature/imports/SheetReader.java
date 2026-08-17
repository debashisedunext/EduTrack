package com.edunext.edutrack.api.feature.imports;

/**
 * B-032 · turns one uploaded file into one read sheet.
 *
 * <p>Two implementations, chosen by extension in {@link ImportFileParser}:
 * {@link XlsxSheetReader} and {@link CsvSheetReader}. A third — the
 * {@code HSSFEventFactory} reader that would let {@code .xls} back in, see
 * {@link UnsupportedImportFileException} — would be a file, not a change to
 * anything else, which is the reason this interface exists at two
 * implementations rather than waiting for a third.
 *
 * <p><b>A reader knows nothing about clients, or about schemas at all.</b> That
 * is not incidental tidiness: {@code ImportEngineIsolationTest} fails the build
 * if anything in this package names a business entity, and header auto-matching
 * happens above these, against whatever registration the URL asked for.
 */
interface SheetReader {

    /**
     * @param fileName      as uploaded, used for messages and — for CSV, which has
     *                      no sheets of its own — for the single sheet's name
     * @param content       the whole file. Bounded by
     *                      {@link ImportUploadLimits#maxBytes()}, checked by the
     *                      caller before a reader ever sees it
     * @param requestedSheet which sheet to read, or {@code null} for the first.
     *                       This is the multi-sheet selector
     * @throws UnreadableImportFileException  the content is not what the extension claims,
     *                                        or the sheet is empty, or has no heading row
     * @throws ImportLimitExceededException   past the row or column ceiling. Thrown
     *                                        <em>during</em> the read, not after it
     */
    ParsedSheet read(String fileName, byte[] content, String requestedSheet);
}
