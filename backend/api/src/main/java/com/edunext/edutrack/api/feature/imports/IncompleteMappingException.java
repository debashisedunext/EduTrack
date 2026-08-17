package com.edunext.edutrack.api.feature.imports;

import java.util.List;

/**
 * B-034 · a required column is unmapped — a 422.
 *
 * <p>Blueprint §4B.3 makes this step 3's rule: "unmapped required columns block
 * the Next button". B-033 enforces it in the browser, and this is the same rule
 * on the server, where it has to be for two reasons.
 *
 * <h2>Why the dry run cannot just run</h2>
 *
 * <p>It would produce a preview. With {@code name} unmapped, every row is
 * missing a required value, so all four hundred come back
 * {@link ImportVerdict#REJECTED} reading "Name required" — a screen full of
 * rejections that points at the rows when the fault is one dropdown on the
 * previous step. The user goes looking through their spreadsheet at the column
 * that is, in fact, filled in.
 *
 * <p>And the natural key is worse than that. Unmapped, no row has a key: nothing
 * can be matched, so a preview would say "will create" about clients that exist.
 *
 * <h2>Not a duplicate of the client-side check</h2>
 *
 * <p>The browser's check is what makes the button explain itself before it is
 * pressed. This one is what makes the answer correct for any caller — B-035's
 * commit route takes the same mapping and needs the same guarantee, and neither
 * route should be discovering it from a preview full of noise.
 *
 * @param missing the required fields with no column, in the schema's own
 *                template order — {@link ImportMapping#missingRequired} orders
 *                them, so the complaint reads the same way twice running rather
 *                than in whatever order a hash produced
 */
class IncompleteMappingException extends RuntimeException {

    private final String schemaKey;
    private final List<String> missingFields;
    private final List<String> missingHeaders;

    IncompleteMappingException(String schemaKey, List<ImportField> missing) {
        super(message(missing));
        this.schemaKey = schemaKey;
        this.missingFields = missing.stream().map(ImportField::name).toList();
        this.missingHeaders = missing.stream().map(ImportField::header).toList();
    }

    /**
     * Headers rather than field names, because the user is looking at a
     * spreadsheet: {@code Client Code} is a column they can find and
     * {@code clientCode} is not.
     */
    private static String message(List<ImportField> missing) {
        List<String> headers = missing.stream().map(ImportField::header).toList();
        return headers.size() == 1
                ? "The required column " + headers.getFirst()
                        + " is not mapped to a column in your file."
                : "These required columns are not mapped to a column in your file: "
                        + String.join(", ", headers) + ".";
    }

    String schemaKey() {
        return schemaKey;
    }

    /** The field names, for a client that wants to highlight its own rows. */
    List<String> missingFields() {
        return missingFields;
    }

    /** The same fields as headings, for a message. */
    List<String> missingHeaders() {
        return missingHeaders;
    }
}
