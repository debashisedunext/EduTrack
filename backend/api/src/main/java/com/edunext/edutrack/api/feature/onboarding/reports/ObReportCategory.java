package com.edunext.edutrack.api.feature.onboarding.reports;

/**
 * B-122 · how OB-10 groups its cards. Mirrors {@code ObReportCategory} in the
 * contract (A-118).
 *
 * <p>Four rather than the ticketing hub's five, because plan §10's set does not
 * divide the same way and A-118 says so on the schema: funnel and time-to-live
 * are about {@link #DELIVERY}, stuck-and-aging and sign-off-pending about a
 * {@link #CLIENT}, TAT compliance and CSAT summary about {@link #QUALITY}
 * (alongside the OB4b breach log, held for now), and the sales pipeline about
 * {@link #PIPELINE}.
 *
 * <p>A separate enum from {@code ReportCategory} rather than a shared one, and
 * that is deliberate. The two modules' groupings have one label in common
 * ({@code QUALITY}) and nothing else, and a single enum carrying nine constants
 * would put {@code WORKFLOW} on a hub that has no workflows and
 * {@code PIPELINE} on one that has no sales — which is the same failure
 * {@link ObReportFilterKind} exists to prevent one level down. Plan §2 makes
 * the module boundary the governing rule: "not shared (domain): every table,
 * feature package, route, permission string, report."
 */
enum ObReportCategory {

    /** Are journeys moving? The funnel and time-to-live. */
    DELIVERY,

    /** What is a client waiting on, or being waited on for? Stuck-and-aging, sign-offs. */
    CLIENT,

    /** Are we hitting what we promised? TAT compliance, CSAT summary, and the OB4b breach log. */
    QUALITY,

    /** Where did the book come from? The sales pipeline. */
    PIPELINE
}
