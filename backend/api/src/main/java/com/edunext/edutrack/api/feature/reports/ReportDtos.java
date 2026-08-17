package com.edunext.edutrack.api.feature.reports;

import java.util.List;
import java.util.Map;

/**
 * A-063 · the shapes S-27 answers with. Mirrors {@code ReportCatalogueResponse}
 * and {@code ReportResponse} in the contract.
 *
 * <p>One generic column/row shape carries all eighteen reports, which is
 * D-001's decision and not this task's: a scorecard, a funnel and a delivery
 * log are all "labelled columns over rows" once the drawing is the client's
 * problem. Eighteen bespoke response types would be eighteen chances for the
 * export engine (A-064) to need a special case.
 */
public final class ReportDtos {

    private ReportDtos() {
    }

    // ── catalogue ───────────────────────────────────────────────────────────

    record CatalogueResponse(Catalogue data) {
    }

    /**
     * @param scopeNote what the caller's rows will be narrowed to, or null for
     *                  an Admin who sees everything. Stated once here rather
     *                  than repeated on eighteen cards, and stated at all
     *                  because a Developer reading "Resource Performance
     *                  Scorecard" would otherwise reasonably expect to pick a
     *                  colleague — §2 gives them "Own perf." and the hub should
     *                  say so before they open a report, not after.
     */
    record Catalogue(List<Descriptor> reports, String scopeNote) {
    }

    /**
     * One card on the hub, and the viewer's instructions for rendering it.
     *
     * @param chart             the visual §7.8 names, or null for a table-only
     *                          report. Where set, the viewer draws the chart
     *                          <b>and</b> the table — never the chart alone,
     *                          because a chart cannot be read for exact values
     *                          and this is the screen people open to get a
     *                          number they intend to quote.
     * @param filters           only the filters this report's runner honours.
     *                          Drawing a control the runner ignores is worse
     *                          than omitting it: the user sets it, nothing
     *                          changes, and the screen looks broken.
     * @param available         false for a report declared but not yet built.
     * @param unavailableReason present exactly when {@code available} is false.
     *                          {@code ReportCatalogueTest} pins the pairing in
     *                          both directions — a card greyed out with no
     *                          explanation is the state this whole approach
     *                          exists to avoid.
     */
    public record Descriptor(String key, String title, String description, ReportCategory category,
                             String chart, List<ReportFilterKind> filters,
                             boolean available, String unavailableReason) {
    }

    // ── a run ───────────────────────────────────────────────────────────────

    record ReportResponse(Report data, RunMeta meta) {
    }

    record Report(String reportKey, List<Column> columns, List<Map<String, Object>> rows) {
    }

    /**
     * @param type how the client formats and aligns the cell, and how A-064
     *             will write it into a spreadsheet. Numbers right-align and
     *             durations are hours rather than millis; sending both as
     *             strings would push those decisions into eighteen renderers.
     */
    record Column(String key, String label, ColumnType type) {
    }

    enum ColumnType {
        STRING, NUMBER, DATE, DURATION, PERCENT;

        /** The contract spells these lower-case; the enum is Java's convention. */
        @com.fasterxml.jackson.annotation.JsonValue
        String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * @param appliedScope what the server actually narrowed the rows to, in
     *                     words. Present because a delivery role's
     *                     {@code ?resourceId=} is ignored <i>silently</i>:
     *                     without this, a filter that did nothing and a filter
     *                     that matched nothing look identical, and the second
     *                     is a data answer while the first is a permissions
     *                     one.
     */
    record RunMeta(String appliedScope) {
    }
}
