package com.edunext.edutrack.api.feature.imports;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * B-030 · an uploaded workbook, parsed but not yet mapped or judged.
 *
 * <p>What step 2 produces and steps 3, 4 and 5 read. It exists because the
 * wizard spans four requests and the file is uploaded in the first: without it,
 * either the browser re-uploads for every dry run, or the mapping screen carries
 * 5,000 rows through the client and back.
 *
 * <p><b>Rows are keyed by the user's own column headings</b>, not by our field
 * names — mapping has not happened yet, and it can change between the dry run
 * and the commit if the user goes back a step. {@link ImportMapping#apply} is
 * what converts these, per request, from whatever the mapping is at that moment.
 *
 * @param sheets every sheet in the workbook, for the multi-sheet selector; the
 *               first is the default
 * @param sheet  the one {@code rows} was read from
 */
public record StagedUpload(
        UUID uploadId,
        String fileName,
        List<String> sheets,
        String sheet,
        List<String> headers,
        List<Map<String, String>> rows,
        Instant stagedAt) {

    public StagedUpload {
        sheets = List.copyOf(sheets);
        headers = List.copyOf(headers);
        rows = rows.stream().<Map<String, String>>map(Map::copyOf).toList();
    }

    public int rowCount() {
        return rows.size();
    }
}
