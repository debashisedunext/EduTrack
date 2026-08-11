package com.edunext.edutrack.api.feature.imports;

import java.util.List;
import java.util.Objects;

/**
 * B-030 · one column of a registered schema, declared once.
 *
 * <p>This record is the reason the wizard is one engine rather than five loosely
 * agreeing features. A single declaration feeds every step:
 *
 * <table border="1">
 *   <caption>Who reads what</caption>
 *   <tr><th>Step</th><th>Reads</th></tr>
 *   <tr><td>B-031 template</td><td>{@link #header()} for the column, {@link #allowedValues()} for the data-validation dropdown, {@link #example()} for the filled example row</td></tr>
 *   <tr><td>B-033 mapping</td><td>{@link #header()} to auto-match, {@link #required()} to decide whether Next is blocked</td></tr>
 *   <tr><td>B-034 dry run</td><td>{@link #required()}, {@link #maxLength()}, {@link #type()}, {@link #validators()}</td></tr>
 *   <tr><td>B-036 error report</td><td>{@link #header()}, so the report's columns match the file the user uploaded</td></tr>
 * </table>
 *
 * <p>The dropdown case is the one that pays for itself immediately: because
 * B-031's template validation and B-034's {@code ENUM} check read the same
 * {@code allowedValues}, <b>the template cannot offer a value the import
 * rejects.</b> Held as two lists, they drift the first time somebody adds a
 * support plan.
 *
 * <p>Built fluently — {@code ImportField.required("clientCode", "Client
 * Code").maxLength(20).validate(...)} — because a schema is twenty of these in a
 * row and twenty eight-argument constructors is unreadable. The withers return
 * new instances; the record stays immutable and safe to hold in a singleton
 * registration.
 *
 * @param name          the target field, matching the entity property and the
 *                      key used in the wizard's {@code mapping} object
 * @param header        the human column heading, in the template and the file
 * @param required      whether a blank cell rejects the row
 * @param maxLength     column width, or 0 for unbounded. Checked here rather
 *                      than left to MySQL: a truncation error surfaces as a
 *                      failed batch mid-commit, where the dry run can name the
 *                      row before anything is written
 * @param allowedValues the {@code ENUM} domain, empty otherwise
 * @param example       the value B-031 puts in the example row. A template with
 *                      a worked example produces far fewer rejected rows than
 *                      one with only headers
 * @param type          see {@link ImportFieldType}
 * @param validators    extra per-value rules, run in order after the structural
 *                      checks
 */
public record ImportField(
        String name,
        String header,
        boolean required,
        int maxLength,
        List<String> allowedValues,
        String example,
        ImportFieldType type,
        List<FieldValidator> validators) {

    public ImportField {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(header, "header");
        allowedValues = List.copyOf(allowedValues);
        validators = List.copyOf(validators);
        if (type == ImportFieldType.ENUM && allowedValues.isEmpty()) {
            // An ENUM with no domain accepts everything, which is not what the
            // author meant and is invisible until a bad value reaches the table.
            throw new IllegalArgumentException(
                    "ENUM field '" + name + "' declares no allowedValues");
        }
    }

    /** A column a row cannot omit. */
    public static ImportField required(String name, String header) {
        return new ImportField(name, header, true, 0, List.of(), null,
                ImportFieldType.TEXT, List.of());
    }

    /** A column a row may leave blank. */
    public static ImportField optional(String name, String header) {
        return new ImportField(name, header, false, 0, List.of(), null,
                ImportFieldType.TEXT, List.of());
    }

    public ImportField maxLength(int maxLength) {
        return new ImportField(name, header, required, maxLength, allowedValues,
                example, type, validators);
    }

    public ImportField type(ImportFieldType type) {
        return new ImportField(name, header, required, maxLength, allowedValues,
                example, type, validators);
    }

    /** Declares the {@code ENUM} domain and sets the type in one move, so the two cannot disagree. */
    public ImportField oneOf(String... values) {
        return new ImportField(name, header, required, maxLength, List.of(values),
                example, ImportFieldType.ENUM, validators);
    }

    public ImportField example(String example) {
        return new ImportField(name, header, required, maxLength, allowedValues,
                example, type, validators);
    }

    public ImportField validate(FieldValidator... extra) {
        List<FieldValidator> combined = new java.util.ArrayList<>(validators);
        combined.addAll(List.of(extra));
        return new ImportField(name, header, required, maxLength, allowedValues,
                example, type, List.copyOf(combined));
    }
}
