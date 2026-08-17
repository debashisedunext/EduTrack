package com.edunext.edutrack.api.feature.reports;

/**
 * A-063 · how S-27's card grid is grouped.
 *
 * <p>§7.8 lists eighteen reports in one flat table. Eighteen ungrouped cards is
 * a wall, and the question a person actually arrives with is a subject —
 * "something about how the team is doing", "something about quality" — not a
 * report name they already know. Grouping is therefore part of the hub rather
 * than decoration on it.
 *
 * <p>Five groups for eighteen reports averages under four cards each, which is
 * the point: a group large enough to need scanning is a group that has not
 * helped. The names are deliberately about <em>subject</em> and not about
 * <em>audience</em> — "Manager reports" would be wrong the first time a
 * Developer opened their own scorecard, which §2 explicitly permits.
 */
public enum ReportCategory {

    /** People and their output — scorecard, velocity, effort, contribution, the 360 profile. */
    PEOPLE,

    /** What is being delivered and when — date-wise, project health, aging, SLA breach. */
    DELIVERY,

    /** Signals that something is going wrong repeatedly — reopens, rework, born-critical. */
    QUALITY,

    /** How work moves through the ribbon — stage funnel, stage cycle time, deployment. */
    WORKFLOW,

    /** The system's own record of itself — audit/compliance, email delivery, client reports. */
    OPERATIONS
}
