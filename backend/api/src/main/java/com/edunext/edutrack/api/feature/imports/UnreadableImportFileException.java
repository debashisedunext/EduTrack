package com.edunext.edutrack.api.feature.imports;

import java.util.List;

/**
 * B-032 · the right extension over content that cannot be read — answered 422.
 *
 * <p>Separate from {@link UnsupportedImportFileException} (415) because the two
 * are different problems with different fixes. 415 means "we do not read this
 * kind of file" and the user's move is to convert it; 422 means "we do read this
 * kind of file and this one is broken, or empty, or has no heading row" and the
 * user's move is to look at the file. Collapsing them into one status would make
 * a corrupt {@code .xlsx} and a {@code .pdf} renamed to {@code .xlsx} report the
 * same thing, and only one of those is a mistake the user can see.
 *
 * <p>Nothing here quotes the underlying parser's message to the caller. A POI
 * stack trace names internal parts and offsets, tells an administrator nothing
 * they can act on, and is exactly the sort of detail that should stay in the log.
 */
class UnreadableImportFileException extends RuntimeException {

    /** Non-empty only for {@link #unknownSheet}, which is the case with a choice attached. */
    private final List<String> sheets;

    private UnreadableImportFileException(String message, List<String> sheets, Throwable cause) {
        super(message, cause);
        this.sheets = List.copyOf(sheets);
    }

    static UnreadableImportFileException corrupt(String extension, Throwable cause) {
        return new UnreadableImportFileException(
                ("The file could not be read as a .%s. It may be corrupt, or it may be a "
                        + "different kind of file that has been renamed.").formatted(extension),
                List.of(), cause);
    }

    static UnreadableImportFileException noSheets() {
        return new UnreadableImportFileException(
                "The workbook contains no sheets.", List.of(), null);
    }

    /**
     * A sheet with nothing in it, or with nothing in its first row.
     *
     * <p>The heading row is what everything downstream keys on — B-033 matches on
     * it and the staged rows are keyed by it — so a sheet without one cannot be
     * mapped at all. Better refused here, naming the sheet, than accepted as
     * "0 rows, no columns" and left to look like a working upload.
     */
    static UnreadableImportFileException noHeaderRow(String sheet) {
        return new UnreadableImportFileException(
                ("Sheet '%s' has no heading row. The first row that contains anything is read "
                        + "as the column headings.").formatted(sheet),
                List.of(), null);
    }

    static UnreadableImportFileException unknownSheet(String requested, List<String> available) {
        return new UnreadableImportFileException(
                "The file has no sheet called '%s'.".formatted(requested), available, null);
    }

    /** The sheets the file does have, for the unknown-sheet case. Empty otherwise. */
    List<String> sheets() {
        return sheets;
    }
}
