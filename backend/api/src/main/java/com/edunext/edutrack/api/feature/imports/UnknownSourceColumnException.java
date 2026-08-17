package com.edunext.edutrack.api.feature.imports;

import java.util.List;

/**
 * B-034 · the mapping names a column this sheet does not have — a 422.
 *
 * <p>The counterpart to {@link UnknownImportFieldException}, one side of the
 * mapping over. That one refuses a target field we do not declare; this one
 * refuses a source column their file does not contain.
 *
 * <h2>Why this is not checked when a preset is saved</h2>
 *
 * <p>It cannot be. {@link ImportMappingPresetService#save} says so at length: a
 * preset exists to be applied to a <em>different</em> file, so "does this column
 * exist" has no answer at save time — the file it was saved from is the one file
 * the answer is irrelevant for. The question only becomes answerable here, with
 * a sheet in hand, which is why the check lives at the dry run.
 *
 * <h2>Why it is refused rather than dropped</h2>
 *
 * <p>{@link ImportMapping#apply} reads the cell by heading, so a heading the row
 * does not have yields nothing and the field is simply absent. For an optional
 * field that is silent: the preview says "will update — Name", the commit runs,
 * and the Support Email column the user carefully mapped was never read. Nothing
 * on the screen said so, because to every step downstream the column was blank.
 *
 * <p>The realistic cause is not a typo but a preset applied to a renamed export,
 * which is the ordinary way this happens and the reason B-033's
 * {@code applyPreset} reports its own dropped entries. The server sees the same
 * mismatch when a caller sends a mapping it did not get from that screen.
 *
 * @param headers the sheet's own headings, so the caller can offer the right one
 *                rather than asking the user to compare two lists by eye
 */
class UnknownSourceColumnException extends RuntimeException {

    private final String sheet;
    private final List<String> unknownColumns;
    private final List<String> headers;

    UnknownSourceColumnException(String sheet, List<String> unknownColumns, List<String> headers) {
        super(message(unknownColumns, sheet));
        this.sheet = sheet;
        this.unknownColumns = List.copyOf(unknownColumns);
        this.headers = List.copyOf(headers);
    }

    private static String message(List<String> unknown, String sheet) {
        String quoted = unknown.stream().map(column -> "'" + column + "'").reduce(
                (a, b) -> a + ", " + b).orElse("");
        return unknown.size() == 1
                ? "The mapping reads column " + quoted + ", which the '" + sheet
                        + "' sheet does not have."
                : "The mapping reads columns " + quoted + ", which the '" + sheet
                        + "' sheet does not have.";
    }

    String sheet() {
        return sheet;
    }

    List<String> unknownColumns() {
        return unknownColumns;
    }

    List<String> headers() {
        return headers;
    }
}
