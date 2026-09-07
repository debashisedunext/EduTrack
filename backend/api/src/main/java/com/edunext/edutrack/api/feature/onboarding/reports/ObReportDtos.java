package com.edunext.edutrack.api.feature.onboarding.reports;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * B-122 · the shapes OB-10 answers with. Mirrors {@code ObReportCatalogueResponse}
 * and {@code ObReportResponse} in the contract (A-118).
 *
 * <p>One generic column/row shape carries every report, which is D-001's
 * decision on the ticketing side and is inherited here for the reason that made
 * it right there: a funnel, a scorecard and a pending list are all "labelled
 * columns over rows" once the drawing is the client's problem, and a bespoke
 * response type per report would be a bespoke case in the export engine per
 * report.
 *
 * <h2>Why these are not {@code ReportDtos}</h2>
 *
 * <p>The two are the same idea and not the same type, and the difference is
 * load-bearing in both directions. This module's {@link ColumnType} has
 * {@link ColumnType#RAG} and no {@code TREND}; the ticketing one has
 * {@code TREND} and no {@code RAG}. Merging them would give OB-10 a trend arrow
 * it has no runner for and give S-27 a health chip it has no colour scale for —
 * and each would then be describable in the other module's contract, which plan
 * §2 forbids: "not shared (domain): every table, feature package, route,
 * permission string, report."
 *
 * <p>What <em>is</em> shared is the export engine, and it is shared at the one
 * seam where the two shapes genuinely coincide — see
 * {@link ObReportExportService}, which translates these columns into
 * {@code ReportDtos.Column} for {@code ExportDelivery}. That is a short mapping
 * in one place rather than a merged vocabulary in two contracts.
 */
final class ObReportDtos {

    private ObReportDtos() {
    }

    // ── catalogue ───────────────────────────────────────────────────────────

    record ObReportCatalogueResponse(Catalogue data) {
    }

    /**
     * @param scopeNote what this caller's rows will be narrowed to, or null for
     *                  a role that sees everything. Stated once above the grid
     *                  rather than repeated on twelve cards, and stated at all
     *                  because an OB_STEP_OWNER reading "TAT compliance by
     *                  service and owner" would otherwise reasonably expect to
     *                  pick a colleague — plan §3 gives them their own
     *                  services, and the honest place to say so is before they
     *                  open a report rather than after.
     */
    record Catalogue(List<ObReportDescriptor> reports, String scopeNote) {
    }

    /**
     * One card on the hub, and the viewer's instructions for rendering it.
     *
     * @param chart             the visual, or null for a table-only report.
     *                          Where set the viewer draws the chart <b>and</b>
     *                          the table, never the chart alone — a chart
     *                          cannot be read for an exact value and this is
     *                          the screen people open to get a number they
     *                          intend to quote.
     * @param filters           only the filters this report's runner honours.
     * @param available         false for a report declared but not built.
     * @param unavailableReason present exactly when {@code available} is false.
     *                          {@code ObReportCatalogueTest} pins the pairing
     *                          in both directions — a card greyed out with no
     *                          explanation is the state this whole approach
     *                          exists to avoid.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ObReportDescriptor(String key, String title, String description,
                              ObReportCategory category, Chart chart,
                              List<ObReportFilterKind> filters,
                              boolean available, String unavailableReason) {

        /** A built report: available, with nothing to explain. */
        static ObReportDescriptor built(String key, String title, String description,
                                        ObReportCategory category, Chart chart,
                                        List<ObReportFilterKind> filters) {
            return new ObReportDescriptor(key, title, description, category, chart,
                    filters, true, null);
        }

        /** A report the contract declares and this deployment cannot run yet. */
        static ObReportDescriptor held(String key, String title, String description,
                                       ObReportCategory category, Chart chart,
                                       List<ObReportFilterKind> filters, String reason) {
            return new ObReportDescriptor(key, title, description, category, chart,
                    filters, false, reason);
        }
    }

    /**
     * The visual a report is drawn as.
     *
     * <p>An enum rather than a free string because the viewer switches on it
     * exhaustively, and {@code stacked-bar} is the reason {@link #wire()} is
     * explicit rather than a lower-cased name.
     */
    enum Chart {

        LINE("line"),
        BAR("bar"),
        STACKED_BAR("stacked-bar"),
        FUNNEL("funnel"),
        DONUT("donut");

        private final String wire;

        Chart(String wire) {
            this.wire = wire;
        }

        @JsonValue
        String wire() {
            return wire;
        }
    }

    // ── a run ───────────────────────────────────────────────────────────────

    record ObReportResponse(Report data, RunMeta meta) {
    }

    record Report(String reportKey, List<Column> columns, List<Map<String, Object>> rows) {
    }

    /**
     * @param type how the client formats and aligns the cell, and how the
     *             export engine writes it.
     */
    record Column(String key, String label, ColumnType type) {
    }

    /**
     * How the client formats a cell.
     *
     * <p>{@link #RAG} renders as a chip rather than as text, and {@link #DURATION}
     * is <b>working hours</b> throughout this module — A-118 states both on the
     * contract. The second is the one with teeth: every duration on this
     * surface has already been through {@code WorkingHoursService}, so a client
     * formatting one must not re-derive it from two timestamps, and a runner
     * producing one must not subtract two instants. {@code ObReportDtosTest} is
     * where the wire spellings are pinned; the runners' own tests are where the
     * calendar half is.
     *
     * <p>There is deliberately no {@code TREND}. The ticketing scorecard has
     * one and no report here does — a trend column with no runner behind it
     * would be a rendering path nothing exercises.
     */
    enum ColumnType {

        STRING, NUMBER, PERCENT, DURATION, DATE, RAG;

        /** The contract spells these lower-case; the enum is Java's convention. */
        @JsonValue
        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * @param appliedScope what the server actually narrowed the rows to, in
     *                     words. On the response because a filter the caller
     *                     sent may have been ignored — {@code runObReport} on
     *                     {@code ownerUserId}. Without it, "the filter did
     *                     nothing" and "the filter matched nothing" look
     *                     identical on screen, and only one of them is a
     *                     statement about the data.
     * @param computedAt   when the underlying rows were last computed, or null
     *                     for a report read live. Every report in this task
     *                     reads the journey tables directly and therefore sends
     *                     the instant the query ran; the field is nullable
     *                     because a later report reading a summary table would
     *                     send that table's {@code computed_at} instead, and
     *                     the two are different claims.
     */
    record RunMeta(String appliedScope, Instant computedAt) {
    }
}
