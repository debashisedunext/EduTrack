package com.edunext.edutrack.api.feature.imports;

import java.util.List;

/**
 * B-032 · what a {@link SheetReader} hands back — one sheet, read.
 *
 * <p>Deliberately not a {@link StagedUpload}: this has no id, no staging
 * timestamp and no knowledge that a wizard exists. A reader's whole job is
 * turning bytes into rows, and keeping the staging concepts out of its return
 * type is what lets {@link CsvSheetReader} be forty lines rather than a second
 * copy of the upload flow.
 *
 * @param sheets every sheet the file contains, in workbook order — the list the
 *               multi-sheet selector renders. A CSV has exactly one
 * @param sheet  which of them {@code headers} and {@code rows} came from
 */
record ParsedSheet(List<String> sheets, String sheet, List<String> headers, List<StagedRow> rows) {

    ParsedSheet {
        sheets = List.copyOf(sheets);
        headers = List.copyOf(headers);
        rows = List.copyOf(rows);
    }
}
