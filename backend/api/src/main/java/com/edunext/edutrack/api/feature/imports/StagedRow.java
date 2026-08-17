package com.edunext.edutrack.api.feature.imports;

import java.util.Map;

/**
 * B-032 · one parsed sheet row, still keyed by the user's own column headings.
 *
 * <p>The difference between this and {@link ImportRow} is mapping: that one is
 * keyed by <em>our</em> field names and exists after step 3, this one is what
 * came out of the file at step 2.
 *
 * <p><b>The row number is carried rather than inferred, and that is the whole
 * reason this record exists.</b> Until B-032 a {@link StagedUpload} held a bare
 * {@code List<Map<String, String>>}, which left the dry run to compute the row
 * number as {@code index + 2}. That is right only while every row between the
 * header and this one survived parsing — and blank rows do not. Excel leaves
 * trailing blanks behind constantly, a user who deletes a bad row mid-file
 * leaves a gap, and either one shifts every number below it. The result would be
 * step 4 saying "row 41: Client Code is required" about a row the user can see
 * is fine, which is worse than saying nothing: they go and check, find the cell
 * filled in, and stop believing the preview.
 *
 * <p>{@code number} is 1-based over the sheet, header included, so the first
 * data row is 2 — exactly what Excel's own gutter shows and exactly what
 * {@link ImportRow#rowNumber()} promises. The point of the number is that
 * somebody can go and look at it.
 *
 * @param cells trimmed cell text, keyed by heading. Blank and whitespace-only
 *              cells are absent rather than empty, so "missing" has one
 *              representation here as it does in {@link ImportRow}
 */
public record StagedRow(int number, Map<String, String> cells) {

    public StagedRow {
        cells = Map.copyOf(cells);
    }

    /** Whether the row carried nothing at all — the shape a parser drops. */
    public boolean isEmpty() {
        return cells.isEmpty();
    }
}
