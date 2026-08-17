package com.edunext.edutrack.api.feature.reports;

/**
 * A-063 · which controls S-27's viewer draws for a given report.
 *
 * <p>§7.8 specifies the filter bar as "date range, project, resource, type,
 * level". Those five, plus {@link #CLIENT}, which D-001 already accepted as a
 * query parameter on the runner and which the client report cannot do without.
 *
 * <h2>Why this is declared per report rather than drawn for all</h2>
 *
 * <p>A filter bar showing all six on every report would put a Resource control
 * on the email delivery log, where there is no resource to filter by. The user
 * sets it, nothing changes, and the only available conclusion is that the
 * screen is broken — which is worse than the control being absent, because a
 * missing control asks no question.
 *
 * <p>So each descriptor names the filters its runner actually honours, and the
 * viewer renders exactly those. The rule is the same one {@code WidgetService}
 * applies one screen over: say what cannot be answered rather than accepting
 * input and discarding it.
 */
public enum ReportFilterKind {

    /** `from` / `to`. Almost every report has one; it is not assumed, because the audit log's window is its own. */
    DATE_RANGE,

    PROJECT,

    /**
     * The person a report is about.
     *
     * <p>Present on a descriptor does not mean freely settable: for the three
     * delivery roles {@code ScopeResolver}'s equivalent here forces it to the
     * caller and ignores what was sent. See {@link ReportScope}.
     */
    RESOURCE,

    TASK_TYPE,

    /** Ticket level — LOW/MEDIUM/HIGH/CRITICAL. */
    LEVEL,

    CLIENT
}
