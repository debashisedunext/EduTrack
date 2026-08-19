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
     * @param type      how the client formats and aligns the cell, and how
     *                  A-064 writes it into a spreadsheet. Numbers right-align
     *                  and durations are hours rather than millis; sending both
     *                  as strings would push those decisions into eighteen
     *                  renderers.
     * @param linkTo    B-060 · what this cell names, when it names something
     *                  that has its own screen. Null on every column of the
     *                  first twelve reports, and null is the answer for most
     *                  columns of the next six — a count drills into nothing.
     *                  <b>What, not where</b>: routes live in the frontend's
     *                  {@code entityLinks.ts}, and a server that also spelled
     *                  them would be a second copy of the router that nothing
     *                  keeps in step. See {@link ReportEntityKind}.
     * @param linkIdKey the row key holding the id to link to — {@code
     *                  "clientId"}, not {@code "client"}. A separate key rather
     *                  than reusing the column's own, because the cell shows a
     *                  <em>name</em> and the link needs an <em>id</em>, and the
     *                  id is deliberately carried in the row without a column
     *                  of its own: an internal id is not a figure, and A-064's
     *                  exporter iterates columns, so it stays out of the
     *                  spreadsheet. Present exactly when {@code linkTo} is —
     *                  {@code ReportRunnerContractTest} pins the pairing, since
     *                  a link kind with nothing to key on renders as a dead
     *                  anchor rather than as plain text.
     */
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Column(String key, String label, ColumnType type,
                         ReportEntityKind linkTo, String linkIdKey) {

        /**
         * The ordinary column: a value, formatted by its type, linking nowhere.
         *
         * <p>A three-argument constructor so the twelve runners written before
         * B-060 read exactly as they did. Adding two nulls to roughly seventy
         * existing column declarations would have been a diff in which the one
         * line that gained a link was invisible.
         */
        public Column(String key, String label, ColumnType type) {
            this(key, label, type, null, null);
        }

        /** A column whose cell drills into {@code kind}, keyed by {@code idKey} in the row. */
        static Column linking(String key, String label, ColumnType type,
                              ReportEntityKind kind, String idKey) {
            return new Column(key, label, type, kind, idKey);
        }
    }

    /**
     * How the client formats a cell, and how A-064's exporter writes it.
     *
     * <p>{@code TREND} is B-061's, and is the one type that is not a data type.
     * §7.8 ends the scorecard's column list with "Trend arrows", and the value
     * behind one is an ordinary signed number — so the type exists to say
     * <em>what the number means</em> rather than what it is: a change against
     * the comparable preceding window, to be drawn as a direction and a
     * magnitude rather than as a bare {@code -3}.
     *
     * <p>It buys two things a {@code NUMBER} could not. The arrow, which is what
     * §7.8 asked for and what makes a column of deltas scannable. And exclusion
     * from the chart: {@code ReportChart} plots every numeric column as a
     * series, so the scorecard's bar chart was stacking a signed delta beside an
     * SLA percentage and a cycle time — three quantities with nothing in common
     * but being numbers.
     *
     * <p><b>Direction is not a verdict.</b> The renderer draws up, down or
     * level and says how much; it does not colour one of them good. The same
     * type will carry a reopen-rate trend, where up is bad, and a type that
     * decided from the sign would be wrong on half its uses.
     */
    public enum ColumnType {
        STRING, NUMBER, DATE, DURATION, PERCENT, TREND;

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
