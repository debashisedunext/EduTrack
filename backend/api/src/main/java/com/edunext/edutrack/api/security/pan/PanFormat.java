package com.edunext.edutrack.api.security.pan;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A-113 · normalising, validating and masking a PAN. No key, no I/O, no state.
 *
 * <h2>Normalisation is a correctness rule, not tidiness</h2>
 *
 * <p>The blind index is a deterministic HMAC, so {@code " abcde1234f "} and
 * {@code "ABCDE1234F"} hash to different 32-byte values unless something makes
 * them the same string first. The migration note names the rule — "normalised
 * upper-case and trimmed first" — and it has to live in one place, because the
 * duplicate-client guard is only as good as the agreement between the value
 * stored on create and the value hashed on lookup. Two call sites normalising
 * slightly differently produce a guard that admits duplicates typed in lower
 * case, which is the exact defect §1.1 item 6 exists to prevent.
 *
 * <h2>Masking to the last four, and what the old mask gave away</h2>
 *
 * <p>PHASE-2-BUILD-PLAN.md finding 10: the prototype masked as
 * {@code slice(0,5) + "••••" + slice(9)} — <b>six of ten characters visible,
 * including the whole five-character prefix and the checksum</b>. An Indian PAN
 * is structured, not random: positions 1-3 are an alphabetic series, position 4
 * encodes the holder type (P for individual, C for company, H for HUF…), and
 * position 5 is the first letter of the surname or entity name. Showing that
 * prefix beside a client's name on screen discloses the holder category and
 * corroborates the name; showing the trailing check letter as well leaves four
 * digits between the reader and the whole number.
 *
 * <p>So: <b>the last four characters, and nothing else.</b> The ruling is "mask
 * to the last 4 only", and the length is preserved so the field still reads as
 * a PAN-shaped value rather than a truncated one.
 */
public final class PanFormat {

    /**
     * {@code AAAPL1234C} — five letters, four digits, one letter.
     *
     * <p>Applied to the <em>normalised</em> form, so the pattern needs no
     * case-insensitive flag and cannot disagree with what gets hashed.
     */
    private static final Pattern PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");

    /** How many trailing characters survive masking. */
    private static final int VISIBLE_SUFFIX = 4;

    private static final char MASK_CHAR = '\u2022';

    private PanFormat() {
    }

    /**
     * Trim and upper-case — the single definition of "the same PAN".
     *
     * <p>{@link Locale#ROOT} rather than the default locale, which is not
     * decoration: under a Turkish default {@code "i".toUpperCase()} is
     * {@code "İ"}, so the same PAN typed on two differently-configured servers
     * would hash to two different blind indices and the uniqueness constraint
     * would let both rows in.
     */
    public static String normalise(String raw) {
        return raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
    }

    /** True where the normalised form is a structurally valid PAN. */
    public static boolean isValid(String raw) {
        String normalised = normalise(raw);
        return normalised != null && PAN.matcher(normalised).matches();
    }

    /**
     * The display form: the last four characters, the rest replaced.
     *
     * <p>Null in, null out — a client with no PAN recorded renders as absent
     * rather than as a row of bullets, which would claim a value is held and
     * withheld.
     */
    public static String mask(String raw) {
        String normalised = normalise(raw);
        if (normalised == null || normalised.isEmpty()) {
            return normalised;
        }
        if (normalised.length() <= VISIBLE_SUFFIX) {
            // Nothing to hide behind. Masking every character is the safe
            // reading of a value too short to be a PAN at all: it cannot be
            // partially disclosed, so it is wholly withheld.
            return String.valueOf(MASK_CHAR).repeat(normalised.length());
        }
        int hidden = normalised.length() - VISIBLE_SUFFIX;
        return String.valueOf(MASK_CHAR).repeat(hidden) + normalised.substring(hidden);
    }
}
