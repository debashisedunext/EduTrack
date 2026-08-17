package com.edunext.edutrack.api.feature.imports;

/**
 * B-035 · 422 for {@code skipRejected: false} over a file that has rejections.
 *
 * <p><b>{@code skipRejected: false} means all-or-nothing, not "write them
 * anyway".</b> That reading is the only one available: a row the engine rejected
 * has no valid value to write — a blank client code, an email that is not one, a
 * status outside the enum — so "import it regardless" is not an operation this
 * feature can perform. What the flag can usefully mean is the stricter thing:
 * refuse the whole file unless every row of it is clean.
 *
 * <p>That is a real requirement rather than a rationalisation of a default.
 * §4B.3's wizard offers "import valid rows only" as a choice, and a choice needs
 * an opposite; an organisation reconciling an export against the master wants
 * the file fixed and re-uploaded rather than three quarters of it landing.
 *
 * <p>Refused before anything is written and before a batch row exists, so a
 * caller who did not mean it can flip one boolean and repeat the request — the
 * staging entry is still there, because nothing consumed it.
 */
class RejectedRowsPresentException extends RuntimeException {

    private final String schemaKey;
    private final int totalRows;
    private final int rejected;
    private final int duplicates;

    RejectedRowsPresentException(String schemaKey, int totalRows, int rejected, int duplicates) {
        super(rejected + " row(s) were rejected and " + duplicates
                + " duplicated an earlier row, out of " + totalRows
                + ". This request asked for all rows or none, so nothing has been written. "
                + "Correct the file and upload it again, or import the valid rows only.");
        this.schemaKey = schemaKey;
        this.totalRows = totalRows;
        this.rejected = rejected;
        this.duplicates = duplicates;
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
