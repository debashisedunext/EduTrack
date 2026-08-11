package com.edunext.edutrack.api.feature.imports;

/**
 * B-030 · what the dry run decided about a row.
 *
 * <p>These four names are {@code ImportRowVerdict.verdict} in
 * {@code contracts/openapi.yaml} and the four outcomes in blueprint §4B.3's
 * step-4 table. They are not an internal vocabulary that happens to be
 * serialised — they <em>are</em> the serialised one.
 */
public enum ImportVerdict {

    /** No row with this natural key exists. The commit inserts. */
    WILL_CREATE,

    /**
     * A row with this natural key already exists. The commit <b>updates it</b>
     * — blueprint §4B.3: existing records updated, never duplicated. Re-uploading
     * a corrected file must not leave two of everything.
     */
    WILL_UPDATE,

    /**
     * An earlier row in this same file already claimed this natural key.
     * The earlier row wins and this one is skipped; the reason names which.
     *
     * <p>Distinct from {@link #REJECTED} on purpose: nothing is wrong with the
     * row's <em>content</em>, and telling a user their perfectly valid row was
     * "rejected" sends them looking for a fault that is not there.
     */
    DUPLICATE_IN_FILE,

    /** The row failed validation. The reason says which rule, in the user's words. */
    REJECTED
}
