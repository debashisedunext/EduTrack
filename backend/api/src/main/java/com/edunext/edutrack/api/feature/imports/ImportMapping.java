package com.edunext.edutrack.api.feature.imports;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B-030 · step 3's answer — which column of their file is which field of ours.
 *
 * <p>Direction matters and is easy to get backwards: this is <b>target field →
 * source column</b>, matching the contract's {@code mapping} object ("Target
 * field → source column"). Inverted, it cannot express the ordinary case of a
 * workbook with two columns we ignore.
 *
 * <p>The engine never learns whether a given entry was auto-matched by
 * {@link HeaderMatcher} or chosen by hand from B-033's override dropdown, and
 * that is deliberate — a manual override that took a different code path from an
 * auto-match would be a second, less-tested importer reached only by the users
 * whose files were unusual enough to need it.
 */
public record ImportMapping(Map<String, String> targetToSource) {

    public ImportMapping {
        targetToSource = Map.copyOf(targetToSource);
    }

    /**
     * The required fields this mapping does not cover — B-033 blocks Next on a
     * non-empty answer.
     *
     * <p>Ordered by the schema's own field order so the message reads in
     * template order rather than in hash order, which changes between runs and
     * makes the same complaint look like a different one.
     */
    public Set<String> missingRequired(List<ImportField> fields) {
        Set<String> missing = new LinkedHashSet<>();
        for (ImportField field : fields) {
            if (field.required() && !targetToSource.containsKey(field.name())) {
                missing.add(field.name());
            }
        }
        return missing;
    }

    /**
     * Turn one raw sheet row, keyed by the user's column headings, into an
     * {@link ImportRow} keyed by our field names.
     *
     * <p>Blank and whitespace-only cells are dropped rather than carried as
     * empty strings, so absence has exactly one representation downstream — see
     * {@link ImportRow}.
     */
    public ImportRow apply(int rowNumber, Map<String, String> rawRow) {
        Map<String, String> values = new HashMap<>();
        targetToSource.forEach((target, sourceColumn) -> {
            String cell = rawRow.get(sourceColumn);
            if (cell != null && !cell.isBlank()) {
                values.put(target, cell.trim());
            }
        });
        return new ImportRow(rowNumber, values);
    }
}
