package com.edunext.edutrack.domain.clients;

import java.util.regex.Pattern;

/**
 * B-028 · <b>one answer to "is this a valid client code?"</b>
 *
 * <p>Blueprint §4B.2 makes {@code client_code} the client's natural key —
 * "unique, used in reports and imports". Two places validated its character set
 * and they did not agree:
 *
 * <ul>
 *   <li>{@code ClientDtos.ClientWriteRequest} (B-026, S-33's form) accepted
 *       letters, digits, hyphens and underscores.</li>
 *   <li>{@code ClientImportSchema} (B-030) applied
 *       {@code FieldValidators.alphanumeric()} — letters and digits <b>only</b>.</li>
 * </ul>
 *
 * <h2>Why that mattered rather than merely differing</h2>
 *
 * <p>{@code client_code} is <b>B-035's upsert key</b>. A client created through
 * S-33 as {@code ACME-IN} is a client the importer is later asked to update by
 * that code — and under the old arrangement the importer rejected the row with
 * "Must be letters and numbers only", against a code the system had issued
 * itself and displays on every report. The visible outcome is not an error
 * message: B-035 upserts on the code, so a rejected row is a client that is
 * <em>not</em> updated, and a re-import that "worked" leaves the record stale
 * with a rejection buried in B-036's error report.
 *
 * <p>The form's reading wins. A hyphen in a client code is ordinary —
 * {@code ACME-IN}, {@code NORTHWIND_UK} — and there is no mechanism that a
 * hyphen breaks: unlike {@code project_code}, this is not a ticket-ID prefix,
 * so nothing parses it positionally. The importer's rule was the stricter of the
 * two and stricter in the direction that refuses real data.
 *
 * <p>Uniqueness is deliberately not here. It is answered against the table
 * ({@code uq_clients_code} plus {@code ClientWriteService}'s field-keyed 409)
 * and, for an import, against the file as well — both need more than one value,
 * which is exactly the split {@code FieldValidators} already documents.
 */
public final class ClientCodeFormat {

    private ClientCodeFormat() {
    }

    /**
     * A compile-time constant so {@code ClientWriteRequest}'s
     * {@code @Pattern(regexp = ...)} can reference it directly — a second copy
     * of the expression in an annotation is precisely the drift this class was
     * written to remove.
     */
    public static final String REGEX = "^[A-Za-z0-9][A-Za-z0-9_-]*$";

    /** The column is {@code VARCHAR(20)}; the minimum is the form's. */
    public static final int MIN_LENGTH = 2;

    public static final int MAX_LENGTH = 20;

    private static final Pattern PATTERN = Pattern.compile(REGEX);

    /** Shape only — length is checked by the caller, which has the field name. */
    public static boolean isValid(String candidate) {
        return candidate != null && PATTERN.matcher(candidate.trim()).matches();
    }

    /**
     * The clause, for a field-keyed message that already names the field —
     * {@code "clientCode " + MESSAGE}, which is what {@code ClientWriteRequest}'s
     * {@code @Pattern} renders.
     */
    public static final String MESSAGE =
            "may contain letters, digits, hyphens and underscores, "
                    + "and must start with a letter or digit";

    /**
     * The same clause as a sentence, for B-034's dry-run preview, whose Reason
     * column is read beside a spreadsheet cell rather than beside a form input.
     *
     * <p>Derived rather than written out, so the two renderings cannot come to
     * describe different rules — which is the entire failure this class exists
     * to have prevented.
     */
    public static final String SENTENCE = "Client code " + MESSAGE;
}
