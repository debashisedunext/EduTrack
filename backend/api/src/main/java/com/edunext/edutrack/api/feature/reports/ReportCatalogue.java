package com.edunext.edutrack.api.feature.reports;

import java.util.List;

import static com.edunext.edutrack.api.feature.reports.ReportCategory.DELIVERY;
import static com.edunext.edutrack.api.feature.reports.ReportCategory.OPERATIONS;
import static com.edunext.edutrack.api.feature.reports.ReportCategory.PEOPLE;
import static com.edunext.edutrack.api.feature.reports.ReportCategory.QUALITY;
import static com.edunext.edutrack.api.feature.reports.ReportCategory.WORKFLOW;
import static com.edunext.edutrack.api.feature.reports.ReportFilterKind.CLIENT;
import static com.edunext.edutrack.api.feature.reports.ReportFilterKind.DATE_RANGE;
import static com.edunext.edutrack.api.feature.reports.ReportFilterKind.LEVEL;
import static com.edunext.edutrack.api.feature.reports.ReportFilterKind.PROJECT;
import static com.edunext.edutrack.api.feature.reports.ReportFilterKind.RESOURCE;
import static com.edunext.edutrack.api.feature.reports.ReportFilterKind.TASK_TYPE;

/**
 * A-063 · every report this deployment knows about, built or not.
 *
 * <h2>Why the catalogue is served and not hardcoded in the client</h2>
 *
 * <p>The hub is a card grid, and something has to say what the cards are. A
 * list living in the frontend would be a second copy of the server's
 * vocabulary — the exact thing {@code /me/notification-preferences} refuses in
 * its own contract description, on the grounds that a newly added event would
 * silently fail to appear. A nineteenth report has the same failure: it would
 * exist, be runnable by URL, and be invisible on the only screen that lists
 * reports.
 *
 * <h2>Why unbuilt reports are listed rather than hidden</h2>
 *
 * <p>Seventeen of the eighteen are not implemented yet — they are A-066, A-067
 * and A-068. They appear here with {@code available = false} and a reason,
 * which is A-056's answer to the same shape one screen over: a widget with no
 * table to answer it returns {@code unavailableReason} rather than an empty
 * series, because an empty result is a claim about the data and a 404 on a
 * legitimate route reads as a bug.
 *
 * <p>Hiding them would make "not built yet" indistinguishable from "does not
 * exist", and the hub would silently change size over three sprints with
 * nothing to explain why. Showing them greyed, with a sentence, is honest about
 * a half-built module and costs one boolean.
 *
 * <p>Running an unbuilt key is still a 404, and the asymmetry is deliberate:
 * the catalogue is where "exists but unbuilt" can be said in words, whereas a
 * runner has no columns to name and no rows to return, so there is nothing
 * truthful for a 200 to carry.
 *
 * <h2>The keys are the contract</h2>
 *
 * <p>Kebab-case, stable, and they appear in URLs, in scheduled-report rows
 * (A-065) and in whatever anybody bookmarks. Renaming one later breaks a saved
 * link and a stored schedule, so they are chosen to describe the question
 * rather than the implementation.
 */
public final class ReportCatalogue {

    private ReportCatalogue() {
    }

    /**
     * The single reason string for everything A-066 to A-068 will build.
     *
     * <p>One sentence, not eighteen bespoke ones: the user's question is "can I
     * run this or not", and seventeen variations on "not yet" would read as
     * seventeen different problems.
     */
    private static final String NOT_BUILT =
            "This report is not built yet. The hub lists it so you can see it is coming rather than "
                    + "wonder whether it exists.";

    /**
     * Declared in the order the hub shows them, grouped by category.
     *
     * <p>{@code date-wise} is the one that runs. It was chosen as the reference
     * implementation because it reads {@code daily_ticket_stats} — a table
     * A-050 built and A-051 has been filling every five minutes since — so it
     * proves the whole path end to end against real numbers without a schema
     * change of its own. It belongs to A-067, which still owns the other five
     * in its group.
     */
    private static final List<ReportDtos.Descriptor> ALL = List.of(

            // ── PEOPLE ──────────────────────────────────────────────────────
            built("resource-scorecard", "Resource Performance Scorecard",
                    "Closed, on-time %, cycle time, effort, variance, reopen rate and utilisation per person.",
                    PEOPLE, "bar", List.of(DATE_RANGE, PROJECT, RESOURCE)),
            built("resource-velocity", "Resource Velocity",
                    "Tickets closed per week per person, or one person in detail with a 4-week average.",
                    PEOPLE, "line", List.of(DATE_RANGE, RESOURCE)),
            built("effort-summary", "Effort Summary",
                    "Hours logged by resource, project and task type, and how many tickets they covered.",
                    PEOPLE, "stacked-bar", List.of(DATE_RANGE, PROJECT, RESOURCE, TASK_TYPE)),
            unbuilt("resource-contribution", "Resource Contribution",
                    "Who moved each ticket, rolled up across the stages they owned.",
                    PEOPLE, "stacked-bar", List.of(DATE_RANGE, PROJECT, RESOURCE)),

            // ── DELIVERY ────────────────────────────────────────────────────
            new ReportDtos.Descriptor("date-wise", "Date-wise Report",
                    "Created against closed and reopened per day, with the net backlog line.",
                    DELIVERY, "line", List.of(DATE_RANGE, PROJECT), true, null),
            built("project-health", "Project Health",
                    "Open, critical and delayed now, against what was raised and closed in the window.",
                    DELIVERY, "bar", List.of(DATE_RANGE, PROJECT)),
            built("aging", "Aging Report",
                    "How long open work has been open, bucketed, with the share over thirty days.",
                    DELIVERY, "bar", List.of(DATE_RANGE, PROJECT)),
            built("sla-breach", "Delayed / SLA Breach",
                    "Every breached ticket, worst first, with how far overdue and its latest remark.",
                    DELIVERY, "bar", List.of(DATE_RANGE, PROJECT, LEVEL, TASK_TYPE)),
            built("workload-capacity", "Workload & Capacity",
                    "What each person is carrying against the working hours they actually have.",
                    DELIVERY, "stacked-bar", List.of(DATE_RANGE, RESOURCE)),

            // ── QUALITY ─────────────────────────────────────────────────────
            built("reopen-analysis", "Reopen Analysis",
                    "Where reopens cluster, by resource, project and task type together.",
                    QUALITY, "bar", List.of(DATE_RANGE, PROJECT, RESOURCE)),
            unbuilt("rework-analysis", "Rework Analysis",
                    "Where tickets bounce backwards, and how often the same pair repeats it.",
                    QUALITY, "bar", List.of(DATE_RANGE, PROJECT, RESOURCE)),
            built("task-type-analysis", "Task Type Analysis",
                    "Volume raised against average resolution time per type — what is eating the team.",
                    QUALITY, "donut", List.of(DATE_RANGE, PROJECT)),

            // ── WORKFLOW ────────────────────────────────────────────────────
            built("stage-funnel", "Stage Funnel",
                    "How many tickets entered each stage, and how many are still sitting there.",
                    WORKFLOW, "bar", List.of(DATE_RANGE, PROJECT)),
            built("stage-cycle-time", "Stage Cycle Time",
                    "Average time per stage, split into hours worked and hours waiting.",
                    WORKFLOW, "stacked-bar", List.of(DATE_RANGE, PROJECT)),
            unbuilt("deployment-report", "Deployment Report",
                    "What was deployed, when, by whom, and what came back.",
                    WORKFLOW, "bar", List.of(DATE_RANGE, PROJECT)),

            // ── OPERATIONS ──────────────────────────────────────────────────
            unbuilt("client-report", "Client Report",
                    "Volume, SLA and open work for one client, shaped to be sent to them.",
                    OPERATIONS, "bar", List.of(DATE_RANGE, CLIENT)),
            unbuilt("audit-compliance", "Audit & Compliance",
                    "Who changed what, with the hash chain's own verdict on each entry.",
                    OPERATIONS, null, List.of(DATE_RANGE, PROJECT, RESOURCE)),
            unbuilt("email-delivery-log", "Email Delivery Log",
                    "Every notification mail, its state and why it failed if it did.",
                    OPERATIONS, null, List.of(DATE_RANGE)));

    /**
     * Reports whose figures are not recorded per person, and so cannot answer
     * one of §2's three delivery roles however they are filtered.
     *
     * <h2>Why this exists, and why it was nearly missed</h2>
     *
     * <p>{@code date-wise} reads {@code daily_ticket_stats}, which is keyed
     * {@code (stat_date, project_id)}. Narrowing it to a Developer's projects
     * still answers "what did your projects do", and the catalogue's
     * {@code scopeNote} would have said <i>"these reports cover your own work
     * only"</i> above it — a sentence that is false about the rows beneath it.
     *
     * <p>Three of its five columns cannot be made true per person at all:
     * a ticket is <b>created</b> by a reporter and <b>reopened</b> by a manager,
     * neither of whom is the assignee, and net backlog is a project's stock.
     * A-062 found and named exactly this for the dashboard's widgets; the same
     * table shape produces the same limit here.
     *
     * <p>So a delivery role is told the report cannot answer them, which is
     * A-056's rule: say what the data cannot express rather than returning a
     * narrower number under a wider label. Found by running the endpoint as a
     * real Developer — every test passed while it was wrong, because the tests
     * asserted the scope <em>note</em> and the rows separately and never that
     * the two agreed.
     */
    private static final java.util.Set<String> NOT_KEPT_PER_PERSON =
            java.util.Set.of("project-health", "aging",
                    "deployment-report", "client-report", "email-delivery-log");

    /**
     * Reports that answer a delivery role from {@code resource_daily_stats}
     * instead, with a narrower column set — and so a narrower filter set.
     *
     * <p>{@code date-wise} is the one that does today. It was briefly in
     * {@link #NOT_KEPT_PER_PERSON} above, which was honest and useless: it left
     * a Developer with eighteen greyed cards and no report at all, when their
     * own table records what they closed, the effort they logged and what they
     * are holding. Withholding a question somebody's own data can answer is not
     * the same as refusing to answer it wrongly.
     *
     * <p>The <b>Project filter is dropped</b> for those callers, not merely
     * ignored. {@code resource_daily_stats} is keyed {@code (stat_date,
     * user_id)} with no project column — A-051 recorded that limitation and it
     * is still true — so a Project control there is one the runner cannot
     * honour, and drawing it would be the "set it and nothing happens" failure
     * the filter declaration exists to prevent.
     */
    private static final java.util.Set<String> ANSWERED_PER_PERSON =
            java.util.Set.of("date-wise", "resource-velocity", "workload-capacity");

    private static final String OWN_WORK_DESCRIPTION =
            "What you closed each day, the effort you logged, and what you are still holding.";

    private static final String NO_PER_PERSON_EQUIVALENT =
            "This report is about a project's work rather than one person's. The figures behind it — "
                    + "tickets created, reopened and the backlog total — are recorded per project and "
                    + "per day, and a ticket is raised by a reporter and reopened by a manager rather "
                    + "than by whoever it is assigned to.";

    /** Every descriptor as declared, ignoring who is asking. Used by tests and by {@link #forScope}. */
    static List<ReportDtos.Descriptor> declared() {
        return ALL;
    }

    /**
     * The catalogue as one caller sees it.
     *
     * <p>Availability is per caller, not global: a report can be built and still
     * be unanswerable for a delivery role. The wire shape is unchanged — the
     * client reads {@code available} and {@code unavailableReason} and does not
     * need to know which of the two reasons applies.
     */
    static List<ReportDtos.Descriptor> forScope(ReportScope scope) {
        if (!scope.ownWorkOnly()) {
            return ALL;
        }
        return ALL.stream().map(ReportCatalogue::forOwnWork).toList();
    }

    /**
     * One descriptor as a delivery role sees it: unchanged, re-described, or
     * withheld.
     */
    private static ReportDtos.Descriptor forOwnWork(ReportDtos.Descriptor d) {
        if (ANSWERED_PER_PERSON.contains(d.key()) && d.available()) {
            return new ReportDtos.Descriptor(d.key(), d.title(), OWN_WORK_DESCRIPTION, d.category(),
                    d.chart(), withoutProjectFilter(d.filters()), true, null);
        }
        return answerableForOwnWork(d) ? d : withheld(d);
    }

    /**
     * The resource-keyed table has no project column, so a Project control on
     * these reports is one the runner cannot honour. Removed rather than left to
     * be ignored — the viewer draws exactly what is declared.
     */
    private static List<ReportFilterKind> withoutProjectFilter(List<ReportFilterKind> filters) {
        return filters.stream().filter(f -> f != PROJECT).toList();
    }

    /** The descriptor for a key as one caller sees it, or empty — which the controller turns into a 404. */
    static java.util.Optional<ReportDtos.Descriptor> find(String key, ReportScope scope) {
        return forScope(scope).stream().filter(d -> d.key().equals(key)).findFirst();
    }

    private static boolean answerableForOwnWork(ReportDtos.Descriptor d) {
        return !NOT_KEPT_PER_PERSON.contains(d.key());
    }

    /**
     * Keeps whichever reason is already true.
     *
     * <p>An unbuilt report stays "not built yet" rather than becoming "not kept
     * per person" — the first is what a Developer can do something about by
     * waiting, and replacing it would tell them a report will never be theirs
     * when in fact it has not been written.
     */
    private static ReportDtos.Descriptor withheld(ReportDtos.Descriptor d) {
        if (!d.available()) {
            return d;
        }
        return new ReportDtos.Descriptor(d.key(), d.title(), d.description(), d.category(),
                d.chart(), d.filters(), false, NO_PER_PERSON_EQUIVALENT);
    }

    private static ReportDtos.Descriptor built(String key, String title, String description,
                                               ReportCategory category, String chart,
                                               List<ReportFilterKind> filters) {
        return new ReportDtos.Descriptor(key, title, description, category, chart, filters, true, null);
    }

    private static ReportDtos.Descriptor unbuilt(String key, String title, String description,
                                                 ReportCategory category, String chart,
                                                 List<ReportFilterKind> filters) {
        return new ReportDtos.Descriptor(key, title, description, category, chart, filters, false, NOT_BUILT);
    }
}
