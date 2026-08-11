package com.edunext.edutrack.api.feature.imports;

/**
 * B-030 · what a column holds, declared once and read by three steps.
 *
 * <p>This is deliberately a small, closed set. It is not a type system — it is
 * the answer to three concrete questions the wizard asks about every column:
 *
 * <ul>
 *   <li><b>B-031</b>: what cell format and data-validation does the template
 *       give this column?
 *   <li><b>B-030</b>: what does "well-formed" mean when the dry run checks it?
 *   <li><b>B-035</b>: how is the string turned into a field on the entity?
 * </ul>
 *
 * <p>A richer type model would be three answers pretending to be one. Anything
 * more specific than these belongs in a {@link FieldValidator} on the field.
 */
public enum ImportFieldType {

    /** Free text. The default, and what everything degrades to. */
    TEXT,

    /**
     * Validated by shape only — see {@link FieldValidators#email()}. Whether the
     * address exists is not knowable at import time and pretending otherwise
     * rejects valid rows.
     */
    EMAIL,

    /** ISO-8601 {@code yyyy-MM-dd}. Excel date cells are normalised to this by B-032. */
    DATE,

    /**
     * One of {@link ImportField#allowedValues()} — the dropdown B-031 writes
     * into the template and the set B-030 checks against. The two come from the
     * same declaration, so a template can never offer a value the import rejects.
     */
    ENUM,

    /** Whole numbers. Excel's numeric cells arrive as {@code 42.0}; B-032 trims the tail. */
    INTEGER
}
