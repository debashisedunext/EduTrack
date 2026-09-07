package com.edunext.edutrack.api.feature.onboarding.reports;

import com.edunext.edutrack.api.security.pan.PanFormat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B-123 · strips what a report must not disclose, once, before anything can
 * read it.
 *
 * <h2>Applied in {@code ObReportService.run}, for the reason scope is</h2>
 *
 * <p>{@code ObReportExportService} makes the argument this class inherits: the
 * export path runs "on the identical call a JSON request makes… an export path
 * that assembled its own query would be a second place for
 * {@link ObReportScope} to be applied, and the one nobody re-checked would be
 * the one that leaked."
 *
 * <p>The same sentence decides where redaction goes. Putting it in the exporter
 * would redact the file and leave the JSON behind it untouched — and the viewer
 * renders that JSON on screen, so the spreadsheet would be safer than the page
 * it was downloaded from. Putting it in each runner makes it six rules that
 * drift. It goes immediately after {@code runner.run(…)}, before the
 * {@code Report}, before the {@code ETag} and before either serialiser, so
 * <b>no unredacted sensitive value exists past that line</b> — there is only
 * one {@code run()}, which is the whole reason that line is worth defending.
 *
 * <p>Redaction before the ETag is deliberate rather than incidental. Hashing
 * the raw rows would give two callers the same validator for two different
 * payloads the moment a role-varying rule is added here, which is how a cache
 * comes to hand one person another's report — the failure
 * {@code ObReportService.etagOf} already puts scope in the hash to avoid.
 *
 * <h2>Untouched rows are not copied</h2>
 *
 * <p>Every report in this package is {@link ObReportSensitivity#ORDINARY}
 * throughout, so the common path is a scan of the column list and a return of
 * the argument. Rebuilding every row map of every report to change nothing
 * would be a real cost on a surface whose own README defends its per-row
 * calendar calls.
 */
final class ObExportRedaction {

    private ObExportRedaction() {
    }

    /**
     * The result with every classified column rewritten.
     *
     * <p>Returns the argument unchanged where nothing is classified, so the
     * identity is meaningful: a caller may compare references to tell whether
     * anything was redacted, and {@code ObExportRedactionTest} does.
     */
    static ObReportRunner.Result apply(ObReportRunner.Result result) {
        List<ObReportDtos.Column> sensitive = result.columns().stream()
                .filter(column -> column.sensitivity() != ObReportSensitivity.ORDINARY)
                .toList();
        if (sensitive.isEmpty()) {
            return result;
        }
        List<Map<String, Object>> rows = result.rows().stream()
                .map(row -> redactRow(row, sensitive))
                .toList();
        return new ObReportRunner.Result(result.columns(), rows);
    }

    /**
     * <p>A new map rather than a mutation of the caller's: a runner is entitled
     * to hand back {@code Map.of(…)}, which throws on {@code put}, and a runner
     * that reuses a row instance across two results would otherwise find the
     * first export had redacted the second. {@link LinkedHashMap} because the
     * export engine writes cells in column order and a report read as JSON is
     * read by a person.
     *
     * <p>Keys absent from the row stay absent. Writing an explicit null for a
     * column the runner did not populate would turn "this report has no such
     * cell" into "this cell was withheld", and the second is a claim about data
     * that may not exist.
     */
    private static Map<String, Object> redactRow(Map<String, Object> row,
                                                 List<ObReportDtos.Column> sensitive) {
        Map<String, Object> redacted = new LinkedHashMap<>(row);
        for (ObReportDtos.Column column : sensitive) {
            if (!redacted.containsKey(column.key())) {
                continue;
            }
            redacted.put(column.key(), redact(column.sensitivity(), redacted.get(column.key())));
        }
        return redacted;
    }

    /**
     * <p>{@code PanFormat} rather than a mask written here — A-113 owns the one
     * definition of what a masked PAN looks like, and its own note explains
     * that two call sites normalising differently is how the rule comes apart.
     * A second implementation in this package would be a second answer to
     * "how much of a PAN is visible", and the export would be the copy nobody
     * updated.
     *
     * <p>A non-string value is stringified rather than passed through. A PAN
     * arriving as something other than a {@code String} is already a defect,
     * and the safe reading of an unexpected type in a column classified as
     * identity data is to mask what it prints as, not to emit it intact.
     */
    private static Object redact(ObReportSensitivity sensitivity, Object value) {
        if (value == null) {
            return null;
        }
        return switch (sensitivity) {
            case PAN -> PanFormat.mask(String.valueOf(value));
            // Null, not "—". The cell is empty in the spreadsheet and absent in
            // the JSON, which is what the engine already writes for a value a
            // report does not have; a placeholder string would arrive in a
            // NUMBER column and make the file unparseable by whatever the
            // finance team opens it with.
            case MONEY -> null;
            case ORDINARY -> value;
        };
    }
}
