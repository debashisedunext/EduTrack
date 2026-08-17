package com.edunext.edutrack.api.feature.imports;

/**
 * B-035 · 422 when no row of the file would be written.
 *
 * <p>Every row rejected, every row a duplicate of an earlier one, or a sheet
 * with no data rows at all. The alternative was to accept it and complete
 * instantly with four zeroes, and that is worse in a way that only shows up
 * later: {@code import_batches} is B-037's audit trail, and a row in it saying a
 * file was imported on Tuesday — when nothing was — is a false entry in the
 * record that exists to make bad imports traceable.
 *
 * <p>It is also the wrong answer to the person. They pressed Import on a screen
 * that had just told them nothing was importable; a green "done" confirms the
 * press rather than the outcome.
 *
 * <p>The counts go on the problem body so the screen can say <em>why</em>
 * nothing is writable without re-running the dry run it just ran.
 */
class NothingToCommitException extends RuntimeException {

    private final String schemaKey;
    private final int totalRows;
    private final int rejected;
    private final int duplicates;

    NothingToCommitException(String schemaKey, int totalRows, int rejected, int duplicates) {
        super(message(totalRows, rejected, duplicates));
        this.schemaKey = schemaKey;
        this.totalRows = totalRows;
        this.rejected = rejected;
        this.duplicates = duplicates;
    }

    private static String message(int totalRows, int rejected, int duplicates) {
        if (totalRows == 0) {
            return "The sheet has no data rows, so there is nothing to import.";
        }
        return "No row in this file can be imported — " + rejected + " rejected and "
                + duplicates + " duplicated an earlier row, out of " + totalRows
                + ". Nothing has been written.";
    }

    String schemaKey() {
        return schemaKey;
    }

    int totalRows() {
        return totalRows;
    }

    int rejected() {
        return rejected;
    }

    int duplicates() {
        return duplicates;
    }
}
