package com.edunext.edutrack.api.feature.imports;

import java.util.List;

/**
 * B-030 · the result of the step-4 dry run — {@code ImportPreviewResponse.data}
 * in the contract.
 *
 * <p><b>Producing one of these writes nothing.</b> That is the point of the step
 * and the reason this type exists at all: the user sees "412 create · 38 update
 * · 6 rejected · 2 duplicates" and decides, before the database has been
 * touched. A bulk import that half-succeeds silently is worse than no import.
 *
 * <p>The counts are derived from the rows rather than accumulated alongside
 * them, so a summary that disagrees with the table below it is not expressible.
 */
public record ImportPreview(
        int willCreate,
        int willUpdate,
        int duplicates,
        int rejected,
        List<ImportRowVerdict> rows) {

    public ImportPreview {
        rows = List.copyOf(rows);
    }

    static ImportPreview of(List<ImportRowVerdict> rows) {
        return new ImportPreview(
                count(rows, ImportVerdict.WILL_CREATE),
                count(rows, ImportVerdict.WILL_UPDATE),
                count(rows, ImportVerdict.DUPLICATE_IN_FILE),
                count(rows, ImportVerdict.REJECTED),
                rows);
    }

    private static int count(List<ImportRowVerdict> rows, ImportVerdict verdict) {
        return (int) rows.stream().filter(r -> r.verdict() == verdict).count();
    }

    /** The rows a commit would write — {@code WILL_CREATE} and {@code WILL_UPDATE} only. */
    public List<ImportRowVerdict> writable() {
        return rows.stream().filter(ImportRowVerdict::isWritable).toList();
    }
}
