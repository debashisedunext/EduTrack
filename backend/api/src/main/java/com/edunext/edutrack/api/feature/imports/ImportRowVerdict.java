package com.edunext.edutrack.api.feature.imports;

import java.util.Map;

/**
 * B-030 · one row of blueprint §4B.3's step-4 table, and one row of B-036's
 * error report.
 *
 * <p>Carries {@code values} as well as the judgement so that both consumers can
 * be built from this alone: the preview renders the row the user is being asked
 * about, and the error report re-emits the rejected row as uploaded with the
 * reason appended. Re-reading the file to produce either would risk showing a
 * verdict next to a different row than the one it was reached on.
 *
 * @param reason {@code null} for a clean {@code WILL_CREATE} / {@code WILL_UPDATE},
 *               matching the contract's nullable {@code reason} and the em dash
 *               in the blueprint's mock-up
 */
public record ImportRowVerdict(
        int rowNumber,
        ImportVerdict verdict,
        String reason,
        Map<String, String> values) {

    public ImportRowVerdict {
        values = Map.copyOf(values);
    }

    static ImportRowVerdict of(ImportRow row, ImportVerdict verdict) {
        return new ImportRowVerdict(row.rowNumber(), verdict, null, row.values());
    }

    static ImportRowVerdict of(ImportRow row, ImportVerdict verdict, String reason) {
        return new ImportRowVerdict(row.rowNumber(), verdict, reason, row.values());
    }

    /** True for the rows a commit will actually touch — what {@code skipRejected} keeps. */
    public boolean isWritable() {
        return verdict == ImportVerdict.WILL_CREATE || verdict == ImportVerdict.WILL_UPDATE;
    }
}
