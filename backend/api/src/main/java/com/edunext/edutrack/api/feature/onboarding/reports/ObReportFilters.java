package com.edunext.edutrack.api.feature.onboarding.reports;

/**
 * B-122 · the filters that are not scope, carried to a runner.
 *
 * <p>A runner reads the ones its descriptor declares and ignores the rest. The
 * catalogue is what promises the user a control does something, so the two have
 * to agree — {@code ObReportRunnerContractTest} is where that is checked, and it
 * exists because {@code ReportFilters} one module over records three filters
 * that were declared on the contract, drawn on the screen and read by nobody
 * for six weeks.
 *
 * <h2>What is deliberately not in here</h2>
 *
 * <p>{@code ownerUserId}. It is the one value with a security consequence — the
 * one {@link ObReportScope#ownerSubject} overrules to the caller themselves for
 * an OB_STEP_OWNER — and it travels as its own argument for the reason
 * {@code ReportFilters} gives for keeping {@code resourceId} out: folding it
 * into a bag of optional preferences is how the next runner comes to read it
 * directly and quietly re-open what §3 withholds.
 *
 * <p>The date range is out for a duller reason. It is honoured by every report
 * here, defaulted by {@link ObReportService} rather than by each runner, and
 * two positional arguments read more clearly at eleven call sites than
 * {@code filters.from()} does.
 *
 * @param productId  one of §4's products, or null for every product. Honoured
 *                   by {@code journey-funnel}, {@code tat-compliance},
 *                   {@code stuck-and-aging} and {@code time-to-live}.
 * @param obClientId one boarding client, or null for every client. Honoured by
 *                   {@code signoff-pending}.
 * @param rag        GREEN / AMBER / RED, or null for every health. Honoured by
 *                   {@code stuck-and-aging}. A string rather than an enum
 *                   because an unrecognised value must narrow to nothing rather
 *                   than become a 400 — a filter every other one of which
 *                   narrows to nothing is not the place to start rejecting
 *                   requests, which is {@code ReportFilters}' own ruling on
 *                   {@code level}.
 */
record ObReportFilters(Long productId, Long obClientId, String rag) {

    static final ObReportFilters NONE = new ObReportFilters(null, null, null);
}
