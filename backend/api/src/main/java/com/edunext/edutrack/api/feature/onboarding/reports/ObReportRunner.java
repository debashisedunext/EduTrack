package com.edunext.edutrack.api.feature.onboarding.reports;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * B-122 · one OB-10 report's implementation.
 *
 * <h2>Why a seam for six implementations written in one task</h2>
 *
 * <p>The same argument {@code ReportRunner} makes, and it applied there across
 * three tasks and eighteen reports. It applies here for a different reason:
 * five more reports are already declared and held as OB4b (PHASE-2-BUILD-PLAN
 * §11.6), and a seventh — prerequisite aging — is waiting on B-124/B-125. Those
 * arrive after this task and after this author, and without a seam the seventh
 * is written by copying the sixth, at which point the scope application and the
 * ETag rule exist twice and start to differ.
 *
 * <p>The narrowness is the point. A runner receives an already-resolved
 * {@link ObReportScope} and an already-overruled {@code ownerSubject}, and
 * cannot widen either: it never sees the request, so it cannot get scoping
 * wrong by forgetting it.
 */
interface ObReportRunner {

    /** The catalogue key this runs. Matched against {@link ObReportCatalogue}. */
    String key();

    /**
     * @param scope        already resolved from the caller — apply it, never
     *                     widen it. Reach it through
     *                     {@link ObReportScope#journeyPredicate} or
     *                     {@link ObReportScope#clientPredicate} rather than by
     *                     branching on {@link ObReportScope#moduleRole()},
     *                     which is how a sixth role would come to be silently
     *                     unrestricted.
     * @param from         inclusive.
     * @param to           inclusive.
     * @param now          the instant this request is being answered at.
     *                     <p>Passed in rather than read from a clock inside
     *                     each runner, and that is not only for testability.
     *                     Three of these reports age something against now —
     *                     how long a step has been stuck, whether a sign-off
     *                     link has died — and the same instant also becomes
     *                     {@code meta.computedAt} above the table. Two runners
     *                     reading the clock separately would produce a report
     *                     whose rows and whose timestamp disagreed by however
     *                     long the query took, which on the one screen people
     *                     open to quote a figure is the wrong place to be
     *                     approximately right.
     * @param ownerSubject whose figures the report is about, already resolved
     *                     by {@link ObReportScope#ownerSubject} — null for
     *                     every owner.
     *                     <p><b>Read this parameter; never read the caller's
     *                     {@code ?ownerUserId=} directly.</b> For an
     *                     OB_STEP_OWNER it has already been overruled to the
     *                     caller themselves, and a runner going back to the raw
     *                     value would hand one implementor a colleague's
     *                     scorecard while {@code meta.appliedScope} went on
     *                     saying it had been narrowed. That is precisely the
     *                     failure {@code ReportRunner} records five ticketing
     *                     runners having shipped.
     * @param filters      the non-scope filters the caller sent, never null.
     */
    Result run(ObReportScope scope, LocalDate from, LocalDate to, Instant now,
               Long ownerSubject, ObReportFilters filters);

    /**
     * @param columns describe themselves, so the viewer and the export engine
     *                can both be generic over every report.
     * @param rows    one map per row, keyed by {@link ObReportDtos.Column#key()}.
     *                A map rather than a typed record because the response is
     *                deliberately generic across differently-shaped reports,
     *                and the exporter iterates columns rather than reflecting
     *                over a class.
     */
    record Result(List<ObReportDtos.Column> columns, List<Map<String, Object>> rows) {
    }
}
