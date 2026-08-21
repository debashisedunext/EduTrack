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
            // A-068 · the description changed with the implementation, because
            // the original one described a report this is not. "Who moved each
            // ticket" is the transitions; the §4A.4 roll-up this actually is
            // reads the effort logs, and says who *did the work* at each stage —
            // which is a different claim and the one the numbers support. See
            // ResourceContributionRunner for why that distinction is what makes
            // this report answerable at all.
            built("resource-contribution", "Resource Contribution",
                    "Hours logged per person per stage, the §4A.4 roll-up widened from one "
                            + "ticket to a whole date range. Effort logged against no stage is "
                            + "kept and grouped, not dropped.",
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
            // B-061 · the description names the limit of the allocation figure,
            // for the reason client-report names the figure it does not have:
            // allocation_pct is nullable and means "not stated", so the total is
            // a floor. The row publishes how many projects stated one, and the
            // card says so before it is opened rather than after.
            //
            // "bar", not "stacked-bar". Stacking asserts the series partition a
            // total and these have never done so — an open ticket is also
            // counted under critical and under delayed, so the bar's height was
            // already double-counting, and B-061's three columns would have
            // stacked a percentage and two counts on top of it. Side-by-side
            // makes no claim about a sum. It is not the whole fix: eight series
            // on one axis is still hard to read at a glance, and that is the
            // chart's owner's call rather than a description change.
            built("workload-capacity", "Workload & Capacity",
                    "What each person is carrying against the working hours they actually have, "
                            + "and what their projects add up to committing them to. Allocation "
                            + "counts only projects where one was stated.",
                    DELIVERY, "bar", List.of(DATE_RANGE, RESOURCE)),

            // ── QUALITY ─────────────────────────────────────────────────────
            built("reopen-analysis", "Reopen Analysis",
                    "Where reopens cluster, by resource, project and task type together.",
                    QUALITY, "bar", List.of(DATE_RANGE, PROJECT, RESOURCE)),
            // A-068 · the description names the dependency, because this report
            // is correct and empty until a ticket's first stage hop is written
            // by something other than a fixture. A card promising "where tickets
            // bounce backwards" that opens to nothing would read as a defect;
            // the honest version says which record it is waiting on. Same
            // reasoning as client-report naming the figure it does not have.
            built("rework-analysis", "Rework Analysis",
                    "Where tickets bounce backwards, how often the same stage pair repeats it, "
                            + "and first-time-right per project. Counted from recorded stage "
                            + "moves, so it stays empty until tickets are moved through the "
                            + "ribbon.",
                    QUALITY, "bar", List.of(DATE_RANGE, PROJECT, RESOURCE)),
            built("task-type-analysis", "Task Type Analysis",
                    "Volume raised against average resolution time per type — what is eating the team.",
                    QUALITY, "donut", List.of(DATE_RANGE, PROJECT)),
            /*
             * A-070 · the nineteenth report, and the first that is not one of
             * §7.8's eighteen. Blueprint §6 asks for it by name — "how many
             * were born critical vs became critical, an insight managers ask
             * for immediately" — and it is the whole reason `original_level`
             * exists and is never overwritten.
             *
             * QUALITY rather than DELIVERY, beside reopen analysis, because it
             * is the same shape of question: not how much work there is, but
             * where something is going wrong. Most of "became critical" is a
             * ticket that ran past its date, which is a statement about us.
             *
             * The description says the lag out loud. Becoming critical happens
             * later than arriving, both halves are counted over the same
             * reported-date cohort, and a reader drawing a trend from recent
             * windows would otherwise conclude things were improving when the
             * only thing that had happened is that the tickets are young.
             */
            built("critical-origin", "Born Critical vs Became Critical",
                    "How much of the critical load arrived that way and how much we created by "
                            + "running late. Counted over the tickets raised in the window, so a "
                            + "recent range understates becoming — young tickets have not had time "
                            + "to breach.",
                    QUALITY, "stacked-bar", List.of(DATE_RANGE, PROJECT, RESOURCE, TASK_TYPE)),

            // ── WORKFLOW ────────────────────────────────────────────────────
            built("stage-funnel", "Stage Funnel",
                    "How many tickets entered each stage, and how many are still sitting there.",
                    WORKFLOW, "bar", List.of(DATE_RANGE, PROJECT)),
            built("stage-cycle-time", "Stage Cycle Time",
                    "Average time per stage, split into hours worked and hours waiting.",
                    WORKFLOW, "stacked-bar", List.of(DATE_RANGE, PROJECT)),
            // A-068 · "by whom" is gone from the description deliberately. A
            // deployment here is a visit to a Deployment-owned stage, and the
            // person who ran it is the stage's assignee at the time — which the
            // sealed hop does record, but which this report does not group by,
            // because §7.8 asks for cadence and outcome rather than for a league
            // table of deployers. Promising a column the report does not have is
            // the defect ReportFilterKind's javadoc describes for filters.
            built("deployment-report", "Deployment Report",
                    "Deployments per week, how many shipped against how many were rolled back, "
                            + "and average working time in the deployment stage. Counted from "
                            + "recorded stage moves, so it stays empty until tickets are moved "
                            + "through the ribbon.",
                    WORKFLOW, "bar", List.of(DATE_RANGE, PROJECT)),

            // ── OPERATIONS ──────────────────────────────────────────────────
            // B-060 · the description names what is absent as well as what is
            // present. §7.8 promises five figures and the schema records four:
            // there is no CSAT column anywhere, and blueprint §17 item 19 puts
            // the rating that would feed one in phase 2–3. Somebody opening
            // this card expecting the fifth should learn why from the hub
            // rather than from a column that is not there.
            built("client-report", "Client Report",
                    "Raised, closed and still open per client, with SLA compliance and average "
                            + "resolution time. Satisfaction is not included — no rating is "
                            + "captured on closure yet.",
                    OPERATIONS, "bar", List.of(DATE_RANGE, CLIENT)),
            // A-068 · the description states the limit of the verdict, which is
            // the whole reason this report is not S-16 with a nicer export. Each
            // entry is checked; the trail's *completeness* is not, and cannot be
            // from a date-ranged slice — A-042 handed that to chain_anchors and
            // the worker. A compliance card that let a reader infer otherwise
            // would be the most consequential overstatement in the product.
            built("audit-compliance", "Audit & Compliance",
                    "Every recorded change to a ticket, with each entry's own hash recomputed "
                            + "and compared. Proves entries have not been altered; completeness "
                            + "of the trail is checked separately by the nightly chain verifier.",
                    OPERATIONS, null, List.of(DATE_RANGE, PROJECT, RESOURCE)),
            // A-068 · says "ticket mail" rather than "every notification mail",
            // because email_log.ticket_id is nullable and §2's row rule is
            // expressed through the ticket's project — so mail with no ticket
            // cannot be scoped and is out. See EmailDeliveryLogRunner.
            built("email-delivery-log", "Email Delivery Log",
                    "Every alert mail raised against a ticket, its state and why it failed if it "
                            + "did. Mail with no ticket — password resets, digests — has no "
                            + "project to scope by and is not included.",
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
                    "deployment-report", "client-report", "email-delivery-log",
                    // A-068 · rework-analysis, and the reason is a shape the
                    // other five do not have: its subject is the person who
                    // *sent work back*, while §2 scopes a delivery role to the
                    // tickets assigned to *them*. Both narrowings then apply and
                    // they name two different people — a Developer would be
                    // served "bounces I caused on tickets I am holding", which
                    // is nearly always empty and is a question nobody asks. Not
                    // a permission problem and not a missing column, so it is
                    // said in the same words A-056 chose: the data does not
                    // record an answer to this for one person.
                    //
                    // resource-contribution is deliberately NOT here. Its
                    // subject is whoever logged the hours, and both narrowings
                    // land on ticket_effort_logs.user_id — the same column, the
                    // same person — exactly as effort-summary already does.
                    "rework-analysis");

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
     *
     * <p><b>Package-private deliberately, as of A-068.</b> This precedence rule
     * was tested through the real catalogue, by finding a report that happened
     * to be unbuilt and asserting it kept its own reason. A-068 built the last
     * five, so there is no longer an unbuilt report to point at — and
     * {@code ReportsIT.unbuiltKey} had already recorded the hazard in its own
     * comment: <i>"a test naming a report that keeps changing state is a test
     * that fails on somebody else's task"</i>. It fell to this task, as
     * predicted.
     *
     * <p>The rule outlives the reports it was written for: a twentieth report
     * will be declared before it is built, and the day it is, this is what stops
     * a Developer being told it will never be theirs. So it is kept and tested
     * against a descriptor the test constructs, rather than deleted because
     * nothing currently exercises it or left tied to whichever report happens to
     * be unfinished.
     */
    static ReportDtos.Descriptor withheld(ReportDtos.Descriptor d) {
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
