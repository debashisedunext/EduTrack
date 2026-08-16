package com.edunext.edutrack.api.feature.imports;

import com.edunext.edutrack.domain.validation.EmailFormat;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * B-030 · blueprint §4B.3's validation rules, written once.
 *
 * <p>"Client code unique and alphanumeric; name required; email format; country
 * against an ISO list" is a client-shaped sentence describing schema-shaped
 * rules. Held on {@code ClientImportSchema} they would be rewritten — slightly
 * differently — for B-038's resource registration, and the two would answer
 * "is this a valid email?" differently by the second release.
 *
 * <p>Uniqueness is deliberately absent. It is not a field rule: it is answered
 * against the file (duplicate-in-file) and against the table (create versus
 * update), both of which need the whole row set and live in
 * {@link ImportValidationEngine}.
 *
 * <p><b>B-028 · {@link #email()} delegates rather than states.</b> The prediction
 * above came true faster than "the second release": B-026 and B-027 gave S-33's
 * form and its contact editor their own answer to "is this a valid email?" —
 * Jakarta's {@code @Email}, which accepts {@code accounts@acme} — on the same
 * columns this reads off a spreadsheet. The canonical statement is
 * {@link EmailFormat}, in {@code domain} because {@code imports} and
 * {@code clients} both already depend on it and neither should depend on the
 * other. Generic, and rightly here: B-038's resource registration validates
 * addresses on the same rule.
 *
 * <p><b>The matching client-code fix is deliberately <em>not</em> here</b>, and
 * {@code ImportEngineIsolationTest} is why — it caught the first attempt. A
 * {@code clientCode()} on the engine's front door would mean the shared engine
 * knowing what a client is, which is the first half of a second import flow.
 * {@code ClientCodeFormat} is client-specific, so it belongs to the
 * registration: see {@code ClientImportSchema}, where entity knowledge is
 * allowed. The distinction is exactly the one B-030 drew and it held.
 */
public final class FieldValidators {

    private FieldValidators() {
    }

    private static final Pattern ALPHANUMERIC = Pattern.compile("^[A-Za-z0-9]+$");

    /**
     * Letters and digits only — a generic field rule, kept for the schemas that
     * genuinely want it.
     *
     * <p><b>B-028 took {@code clientCode} off it.</b> It was never wrong as a
     * rule; it was the wrong rule for that field, which has its own statement on
     * {@link ClientCodeFormat} and permits the hyphens S-33 issues. Left here
     * because {@code TestImportSchema} exercises the engine through it and
     * B-038's resource registration has fields — an employee code — where
     * letters and digits really is the constraint.
     */
    public static FieldValidator alphanumeric() {
        return value -> ALPHANUMERIC.matcher(value).matches()
                ? Optional.empty()
                : Optional.of("Must be letters and numbers only");
    }

    /**
     * B-028 · shape only and deliberately permissive, on
     * {@link EmailFormat}'s single statement of it.
     *
     * <p>The pattern moved rather than changed: B-030 wrote it here, and S-33's
     * form and B-027's contact editor were meanwhile answering the same question
     * with Bean Validation's {@code @Email}, which accepts {@code accounts@acme}.
     * A form that accepts an address the importer rejects means B-035 refusing a
     * row describing a client the system itself created.
     */
    public static FieldValidator email() {
        return value -> EmailFormat.isValid(value)
                ? Optional.empty()
                : Optional.of("Invalid email");
    }

    /**
     * ISO-3166 country, matched on the English display name or either code.
     *
     * <p>Sourced from the JDK's own locale data rather than a checked-in list,
     * which would be a second copy of a standard that changes without us.
     * Matching is case- and whitespace-insensitive because "united kingdom",
     * "GB" and "United Kingdom" are all the same answer typed by three people.
     */
    public static FieldValidator isoCountry() {
        return value -> {
            String candidate = value.trim();
            if (COUNTRIES.contains(candidate.toUpperCase(Locale.ROOT))) {
                return Optional.empty();
            }
            return Optional.of("Not a recognised country");
        };
    }

    private static final Set<String> COUNTRIES = isoCountries();

    private static Set<String> isoCountries() {
        Set<String> names = new java.util.HashSet<>();
        for (String code : Locale.getISOCountries()) {
            names.add(code.toUpperCase(Locale.ROOT));
            Locale country = Locale.of("", code);
            names.add(country.getISO3Country().toUpperCase(Locale.ROOT));
            names.add(country.getDisplayCountry(Locale.ENGLISH).toUpperCase(Locale.ROOT));
        }
        return Set.copyOf(names);
    }

    /** ISO-8601 {@code yyyy-MM-dd}. B-032 normalises Excel's own date cells to this before validation. */
    public static FieldValidator isoDate() {
        return value -> {
            try {
                LocalDate.parse(value.trim());
                return Optional.empty();
            } catch (DateTimeParseException notADate) {
                return Optional.of("Not a date — expected YYYY-MM-DD");
            }
        };
    }

    /**
     * Membership in {@link ImportField#allowedValues()}.
     *
     * <p>Applied by the engine to every {@code ENUM} field rather than declared
     * per field, so a schema author cannot add a dropdown and forget the check
     * that makes it mean anything.
     */
    static FieldValidator oneOf(java.util.List<String> allowed) {
        return value -> allowed.stream().anyMatch(a -> a.equalsIgnoreCase(value.trim()))
                ? Optional.empty()
                : Optional.of("Must be one of: " + String.join(", ", allowed));
    }

    static FieldValidator maxLength(int max) {
        return value -> value.length() <= max
                ? Optional.empty()
                : Optional.of("Longer than " + max + " characters");
    }
}
