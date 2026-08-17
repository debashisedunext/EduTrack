package com.edunext.edutrack.api.feature.imports;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B-032 · the heading row, turned into the keys every staged row is stored under.
 *
 * <p>Shared by both readers because getting it wrong is silent, and it would be
 * wrong in the same way twice.
 *
 * <h2>Duplicate headings are suffixed, not dropped</h2>
 *
 * <p>Rows are keyed by heading — {@link StagedUpload} says so and
 * {@link ImportMapping#apply} depends on it — which means a workbook with two
 * columns both called {@code Email} has two values competing for one map key.
 * Whichever loses is <em>gone</em>: it is not in the headings the mapping screen
 * offers, so the user cannot map it, and nothing anywhere tells them a column of
 * their file was discarded. The second becomes {@code Email (2)}, so both
 * survive and the user picks. {@link HeaderMatcher} still auto-matches the first
 * one, which is the behaviour it already documents ("first wins: a workbook with
 * the heading twice is the user's problem to resolve in the override dropdown").
 *
 * <h2>Trailing blank headings are dropped, interior ones are named</h2>
 *
 * <p>A sheet whose used range runs past the last real column is ordinary — Excel
 * keeps a column alive after its contents are deleted — so blank headings at the
 * end are noise and are cut. A blank heading with real columns after it is a
 * different thing: the column exists, may hold data, and cannot be addressed
 * without a name, so it gets {@code Column D} — its spreadsheet letter, which is
 * the one label the user can find in their own file.
 */
final class SheetHeadings {

    private SheetHeadings() {
    }

    /**
     * @param cells column index → heading text, sparse, already trimmed and with
     *              blanks absent
     * @param limits the column ceiling
     * @return the headings in column order, with index 0 first
     */
    static List<String> from(Map<Integer, String> cells, ImportUploadLimits limits) {
        int lastColumn = cells.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
        if (lastColumn < 0) {
            return List.of();
        }
        int width = lastColumn + 1;
        if (width > limits.maxColumns()) {
            throw ImportLimitExceededException.columns(limits.maxColumns(), width);
        }

        List<String> headings = new ArrayList<>(width);
        Map<String, Integer> seen = new HashMap<>();
        for (int column = 0; column < width; column++) {
            String raw = cells.get(column);
            String heading = raw == null || raw.isBlank() ? columnLetter(column) : raw.trim();

            int occurrence = seen.merge(heading, 1, Integer::sum);
            headings.add(occurrence == 1 ? heading : heading + " (" + occurrence + ")");
        }
        return headings;
    }

    /**
     * Index → the letters Excel puts in the column gutter: 0 is A, 26 is AA.
     *
     * <p>Spelled out rather than borrowed from POI's {@code CellReference} so the
     * CSV reader — which has no POI on its path at all — can use the same rule. A
     * CSV opened in Excel gets these same letters, so the label points at
     * something the user can see either way.
     */
    static String columnLetter(int index) {
        StringBuilder letters = new StringBuilder();
        for (int n = index; n >= 0; n = n / 26 - 1) {
            letters.insert(0, (char) ('A' + n % 26));
        }
        return "Column " + letters;
    }

    /**
     * One data row, keyed by heading.
     *
     * <p>Cells past the last heading are dropped: they have no key to be stored
     * under, and inventing one would put data in the mapping screen under a name
     * that is in no row of the user's file.
     */
    static Map<String, String> row(Map<Integer, String> cells, List<String> headings) {
        Map<String, String> values = new LinkedHashMap<>();
        cells.forEach((column, value) -> {
            if (column < headings.size() && value != null && !value.isBlank()) {
                values.put(headings.get(column), value.trim());
            }
        });
        return values;
    }
}
