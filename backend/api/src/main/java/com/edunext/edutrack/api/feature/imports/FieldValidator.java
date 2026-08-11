package com.edunext.edutrack.api.feature.imports;

import java.util.Optional;

/**
 * B-030 · one reason a cell might be rejected.
 *
 * <p>Returns the <em>reason</em>, not a boolean, because the reason is the
 * product. Blueprint §4B.3's step-4 table has a Message column and B-036's error
 * report appends a Reason column: a validator that answered only true/false
 * would leave both to be reconstructed by whoever calls it, and they would
 * reconstruct them differently.
 *
 * <p>Reasons are written for the person holding the spreadsheet — "Invalid
 * email" and "Code required", as in the blueprint's own mock-up — not for a log.
 */
@FunctionalInterface
public interface FieldValidator {

    /**
     * @param value the trimmed cell, never {@code null} and never blank —
     *              {@link ImportValidationEngine} handles absence via
     *              {@link ImportField#required()} before any validator runs, so
     *              no validator has to repeat that check and none of them can
     *              disagree about what "empty" means.
     * @return the rejection reason, or empty if the value is acceptable
     */
    Optional<String> validate(String value);
}
