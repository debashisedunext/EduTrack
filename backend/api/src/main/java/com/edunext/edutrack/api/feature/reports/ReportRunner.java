package com.edunext.edutrack.api.feature.reports;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A-063 · one report's implementation. A-066 to A-068 add seventeen more.
 *
 * <h2>Why an interface for a single implementation today</h2>
 *
 * <p>Normally that is over-design. Here the alternative is worse in a specific
 * way: the eighteen reports are written by one developer across three tasks
 * over several weeks, and without a seam the second one is written by copying
 * the first, at which point the ETag rule, the scope application and the column
 * contract exist twice and start to differ. {@link ReportService} owns those
 * three concerns and a runner owns only "what are the columns and rows" — so a
 * new report cannot get scoping wrong by forgetting it, because it never sees
 * the request.
 *
 * <p>The narrowness is the point. A runner receives an already-resolved
 * {@link ReportScope} and cannot widen it: the projects it is handed are the
 * caller's, and {@code resourceId} has already been overruled where §2 says it
 * must be.
 */
interface ReportRunner {

    /** The catalogue key this runs. Matched against {@link ReportCatalogue}. */
    String key();

    /**
     * @param scope      already resolved from the caller — apply it, never
     *                   widen it.
     * @param from       inclusive.
     * @param to         inclusive.
     * @param projectIds the projects to read, already intersected with the
     *                   caller's scope by {@link ReportScope#projectFilter}.
     *                   Empty means unrestricted, matching the convention
     *                   everywhere else in this codebase.
     * @param resourceSubject
     *                   B-061 · whose rows the report is about, already
     *                   resolved by {@link ReportScope#resourceSubject} — null
     *                   for every person. The second of the two <b>resolved
     *                   narrowings</b>, sitting beside {@code projectIds} for
     *                   that reason and deliberately not inside
     *                   {@link ReportFilters}: a filter may be ignored, and
     *                   this one may not, because for §2's three delivery roles
     *                   it has already been overruled to the caller themselves.
     *                   <p><b>Read this parameter; never call
     *                   {@code scope.resourceSubject(null)}.</b> Five runners
     *                   did, which returned null for every Admin and PM and
     *                   made {@code ?resourceId=} a control that changed
     *                   nothing on five reports that declare it — while
     *                   {@code meta.appliedScope} went on printing "one
     *                   resource, across all projects". A filter that lies
     *                   about having been applied is worse than one that is
     *                   absent, and {@code ReportRunnersIT.ResourceFilter} is
     *                   where each of the five is now pinned.
     * @param filters    B-060 · the non-scope filters the caller sent, never
     *                   null. A runner reads the ones its descriptor declares
     *                   and ignores the rest — the catalogue is what promises
     *                   the user a control does something, so the two have to
     *                   agree, and {@code ReportRunnerContractTest} is where
     *                   that is checked.
     */
    Result run(ReportScope scope, LocalDate from, LocalDate to, List<Long> projectIds,
               Long resourceSubject, ReportFilters filters);

    /**
     * @param rows one map per row, keyed by {@link ReportDtos.Column#key()}. A
     *             map rather than a typed record because the response is
     *             deliberately generic across eighteen differently-shaped
     *             reports, and A-064's exporter will iterate columns rather
     *             than reflect over a class.
     * @param asOf when the underlying data was last computed, or null for a
     *             report read live. Feeds the {@code ETag}: reports are
     *             expensive and are re-run every time somebody changes a filter
     *             and changes it back, which is what the contract's ETag
     *             paragraph is about.
     */
    record Result(List<ReportDtos.Column> columns, List<Map<String, Object>> rows, java.time.Instant asOf) {
    }
}
