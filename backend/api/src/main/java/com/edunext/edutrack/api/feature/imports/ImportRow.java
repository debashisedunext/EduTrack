package com.edunext.edutrack.api.feature.imports;

import java.util.Map;

/**
 * B-030 · one spreadsheet row, after mapping and before any judgement.
 *
 * <p>Keyed by <b>target field name</b>, not by column heading. That is the whole
 * job of step 3: past this point nothing knows or cares what the user's column
 * was called, whether the header auto-matched or they overrode it by hand, or
 * which sheet it came from. The engine sees the same shape either way.
 *
 * @param rowNumber the 1-based row in the source sheet, header included — so
 *                  the first data row is 2, exactly as the blueprint's step-4
 *                  table shows it and exactly what the user sees in Excel's own
 *                  gutter. An index would be correct and useless: the point of
 *                  the number is that they can go and look at it
 * @param values    trimmed cell values; a cell that was absent, blank or
 *                  whitespace-only is not present in the map at all, so
 *                  "missing" has one representation rather than three
 */
public record ImportRow(int rowNumber, Map<String, String> values) {

    /**
     * Trims here rather than trusting the caller to have done it.
     *
     * <p>{@link ImportMapping#apply} already trims, so this is belt and braces
     * for the one path that matters: a row built any other way — a test, a
     * future importer, B-038 — would otherwise reach validators expecting
     * clean input. {@code "  ACME  "} then fails an alphanumeric check on its
     * own whitespace, and the row is rejected for a fault the user cannot see
     * in their spreadsheet.
     *
     * <p>Making the record enforce its own documented invariant is cheaper than
     * every producer remembering to.
     */
    public ImportRow {
        Map<String, String> trimmed = new java.util.HashMap<>(values.size());
        values.forEach((field, value) -> {
            if (value != null && !value.isBlank()) {
                trimmed.put(field, value.trim());
            }
        });
        values = Map.copyOf(trimmed);
    }

    /** @return the value, or {@code null} if the cell was absent or blank */
    public String get(String field) {
        return values.get(field);
    }
}
