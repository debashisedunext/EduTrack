package com.edunext.edutrack.api.feature.imports;

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
 */
public final class FieldValidators {

    private FieldValidators() {
    }

    private static final Pattern ALPHANUMERIC = Pattern.compile("^[A-Za-z0-9]+$");

    /**
     * Shape only, and deliberately permissive.
     *
     * <p>RFC 5322 done properly rejects addresses that work and accepts ones
     * that do not, and an importer's job is not to adjudicate that. This catches
     * what actually appears in spreadsheets — a missing {@code @}, a trailing
     * comma from a copied list, a bare domain — and lets D-034's bounce handling
     * discover the rest, which is the only thing that can.
     */
    private static final Pattern EMAIL =
            Pattern.compile("^[^\\s@,;]+@[^\\s@,;.]+(\\.[^\\s@,;.]+)+$");

    /** {@code clientCode} is a ticket-facing natural key; punctuation in it breaks the upsert match. */
    public static FieldValidator alphanumeric() {
        return value -> ALPHANUMERIC.matcher(value).matches()
                ? Optional.empty()
                : Optional.of("Must be letters and numbers only");
    }

    public static FieldValidator email() {
        return value -> EMAIL.matcher(value).matches()
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
