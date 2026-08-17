package com.edunext.edutrack.api.feature.imports;

/**
 * B-032 · not a file type this endpoint reads — answered 415.
 *
 * <p><b>{@code .xls} is the case worth explaining, because blueprint §4B.3 lists
 * it and this refuses it.</b> The task line for B-032 is "event-driven SAX
 * parse", and SAX is an XML API: an {@code .xlsx} is a zip of XML parts that can
 * be streamed, while an {@code .xls} is a binary OLE2 container of BIFF records
 * with no XML anywhere in it. The only reader POI offers for the latter that
 * resembles the user model is {@code HSSFWorkbook}, which loads the entire
 * workbook into memory per open — precisely the reader PLAN.md §2.2 bans, and
 * for precisely the reason it bans it.
 *
 * <p>So this is a stated deviation rather than an oversight, and it is stated to
 * the user as well as here: the message names the fix, which is one Save As away
 * and costs them nothing. Supporting the format properly means an
 * {@code HSSFEventFactory} reader beside {@link XlsxSheetReader} — a second
 * {@link SheetReader}, which is the shape this package is already built for.
 */
class UnsupportedImportFileException extends RuntimeException {

    private final String extension;

    UnsupportedImportFileException(String extension, String message) {
        super(message);
        this.extension = extension;
    }

    static UnsupportedImportFileException legacyXls() {
        return new UnsupportedImportFileException("xls",
                "Excel 97–2003 workbooks (.xls) are not accepted. Open the file in Excel, "
                        + "choose Save As, and pick Excel Workbook (.xlsx).");
    }

    static UnsupportedImportFileException unknown(String extension) {
        return new UnsupportedImportFileException(extension,
                extension.isEmpty()
                        ? "The file has no extension. Upload a .xlsx or .csv file."
                        : "'.%s' files are not accepted. Upload a .xlsx or .csv file."
                                .formatted(extension));
    }

    /** Lowercased, without the dot; empty when the name carried none. */
    String extension() {
        return extension;
    }
}
