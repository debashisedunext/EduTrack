package com.edunext.edutrack.api.feature.dashboard;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A-056 · widgets 7–12 of §S-05.
 *
 * <h2>What each widget reads, and why stock is not summed</h2>
 *
 * <p>Three of the six are <b>stock</b> — the donut, the priority split and the
 * aging buckets all answer "what is true now", and A-050's tables record that
 * per day. They therefore read the <em>latest</em> summarised day in the window
 * rather than summing it. This is the mistake worth guarding hardest, because
 * every way it shows up looks fine: a donut summed over a fortnight has exactly
 * the right proportions, and four bars seven times too tall look precisely like
 * four bars. Only the axis gives it away, and nobody reads the axis.
 *
 * <p>The other three are <b>flow</b> — created/closed/reopened per day, and
 * tickets closed per resource per week — and summing those over a range is what
 * they are for.
 *
 * <h2>Role-awareness, and saying so out loud</h2>
 *
 * <p>{@link DashboardScope} makes the same decision the cards make. What is new
 * here is that the decision has an outcome the cards never had: A-050's two
 * tables do not carry the same columns, so for §2's three delivery roles there
 * are widgets with <b>no table that can answer them</b> at all.
 * {@code resource_daily_stats} has no task-type breakdown, no four-way priority
 * split and no created/reopened counts — the last because a ticket is raised by
 * a reporter and reopened by a manager, and neither is the assignee whose
 * dashboard this is.
 *
 * <p>Those widgets return {@code unavailableReason} rather than an empty series
 * or a 404, and the reason is in {@link WidgetDtos.Widget}. Widgets 9 and 10 do
 * work for a delivery role, scoped to themselves.
 *
 * <p><b>A-062 · widget 12 has since joined them.</b> Aging was refused for the
 * same reason until the columns existed; §S-05 names it in the developer
 * variant, so A-062 added them to A-050's table with the project table's own
 * bucket edges and this class grew a branch rather than a second widget key.
 * The three that remain unavailable are unavailable because the question does
 * not apply to an assignee, not because a column is missing — which is why they
 * are not simply the next task's schema change.
 *
 * <h2>🔴 The drill-down window the ticket list does not implement</h2>
 *
 * <p>Every date-windowed drill-down below emits {@code from=} and {@code to=},
 * which is the convention A-055's cards already established.
 * {@code TicketListController} accepts {@code dueFrom}/{@code dueTo} and
 * {@code closedFrom}/{@code closedTo} but has <b>no reported-date window</b>, so
 * those two parameters are currently ignored and the list opens wider than the
 * segment that was clicked — every ticket matching the other filters, for all
 * time, rather than the ones in the window.
 *
 * <p>Emitting a different convention here would have made it worse, not better:
 * the cards and the charts on one screen would then deep-link two different
 * ways, and the fix would have to be made twice. So one convention, one defect,
 * recorded once — {@code reportedFrom}/{@code reportedTo} belongs to A-060,
 * which owns deep-linking, and needs Divyansh's list to grow two parameters.
 */
@Service
class WidgetService {

    /** §S-05's default window, matching {@link DashboardService} exactly. */
    private static final int DEFAULT_WINDOW_DAYS = 30;

    private final WidgetRepository widgets;
    private final DashboardRepository summaries;
    private final Clock clock;

    /** {@code api} publishes no {@code Clock} bean — see {@link DashboardService}'s constructor note. */
    @Autowired
    WidgetService(WidgetRepository widgets, DashboardRepository summaries) {
        this(widgets, summaries, Clock.systemUTC());
    }

    WidgetService(WidgetRepository widgets, DashboardRepository summaries, Clock clock) {
        this.widgets = widgets;
        this.summaries = summaries;
        this.clock = clock;
    }

    /**
     * A-056's six, A-057's three, A-059's one and A-058's four — <b>every key in
     * the contract's enum</b>, as of A-058.
     *
     * <p>That completeness is worth stating because this list has been the
     * shorter half of a pair since A-056, and {@code widget()} returning 404 for
     * a key in the enum was the whole reason it exists. There is no longer a
     * declared-but-unserved key, so a 404 from this route now means a key that
     * is not in the contract at all.
     *
     * <p>{@code DashboardWidgetIT.unimplementedKeysAre404} has had to be
     * re-pointed twice as this list grew — A-057 moved it off {@code sla-gauge}
     * and onto {@code stage-funnel}, which is A-058's. There is nothing left to
     * point it at, and that test has done its job; see its replacement.
     */
    private static final List<String> IMPLEMENTED = List.of(
            "type-donut", "daily-stacked", "velocity", "resource-load", "priority-bar", "aging-buckets",
            "calendar-heatmap", "sla-gauge", "project-treemap", "client-volume",
            "stage-funnel", "rework", "stage-duration", "handoff-latency",
            // Dashboard Rework Dev 2, PR 14. Added here in the SAME commit as
            // the contract's widgetKey enum: DashboardWidgetIT.everyContractKeyIsServed
            // fails from either direction, and #326 learned that the hard way by
            // freezing the key into the contract a fortnight before the branch
            // below existed. A key in the enum is enumerated into the chooser
            // and the batch request, so the dashboard asks for it on first paint.
            "module-open");

    static boolean isImplemented(String widgetKey) {
        return IMPLEMENTED.contains(widgetKey);
    }

    /**
     * The widget and the validator for it, together.
     *
     * <p>They are returned as a pair because the {@code ETag} depends on the
     * <em>resolved</em> window — {@code ?from=} and {@code ?to=} are both
     * optional and default to the last thirty days — and only this class
     * resolves it. Having the controller default the dates so it could build
     * the validator itself would put §S-05's default window in two places, and
     * the day they disagreed every caller who omitted a date would get a
     * validator for a range they were not served.
     */
    record Rendered(WidgetDtos.Widget widget, String etag) {
    }

    @Transactional(readOnly = true)
    Optional<Rendered> widget(CallerIdentity caller, String widgetKey,
                              Long projectId, LocalDate from, LocalDate to) {
        if (!isImplemented(widgetKey)) {
            // Empty means 404 — the key is in the contract's enum but nothing
            // implements it yet. Deliberately not an unavailableReason, which
            // says "your role cannot see this": a PM told their role was the
            // problem would ask an administrator for a permission that would
            // not help, when the honest answer is that A-057 has not landed.
            return Optional.empty();
        }

        Window window = window(from, to);
        DashboardScope scope = DashboardScope.of(caller);
        Instant asOf = summaries.computedAt(window.start(), window.end()).orElse(null);

        WidgetDtos.Widget widget = render(widgetKey, scope, projectId, window, asOf);

        // An unavailable widget gets no validator. Its answer does not depend on
        // computed_at at all — it depends on the caller's role — so an ETag
        // built from the summary tables would let a 304 outlive a role change
        // and keep showing somebody the refusal after they had been promoted.
        String etag = widget.unavailableReason() != null
                ? null
                : etagOf(widgetKey, scope, projectId, window.start(), window.end(), asOf);

        return Optional.of(new Rendered(widget, etag));
    }

    /**
     * A-073 · every requested widget, in one request and one transaction.
     *
     * <h2>Why this exists — a latency argument, not a tidiness one</h2>
     *
     * <p>S-05's first paint asks for ten widgets. Served one HTTP request each
     * that is eleven round trips counting {@code /dashboard/summary}, and A-073's
     * load test showed the cost is almost entirely <em>per request</em> rather
     * than per widget: at 50,000 tickets a widget's own work measured ~7 ms
     * against ~20 ms for the whole call, and all ten widgets landed within 12 ms
     * of each other. There is no slow widget to find. Ten times a fixed cost is
     * the problem, so the only thing that helps is asking ten times less often.
     *
     * <p>Blueprint §9.4 reached this conclusion once already, for the ticket
     * detail page: <i>"Ticket detail loads in one aggregated endpoint
     * ({@code /tickets/:id/full}) to avoid a waterfall of 6 calls."</i> The
     * dashboard had a waterfall of eleven and no equivalent. This is it.
     *
     * <h2>What is saved beyond the round trips</h2>
     *
     * <p>{@code computed_at} was read once per widget — ten identical
     * {@code SELECT MAX(computed_at)} per paint for a value that is the same for
     * all of them by construction. Here the window is resolved once and
     * {@code asOf} read once.
     *
     * <p>That also closes a real inconsistency the per-request version could
     * produce: if A-051 committed a refresh midway through a paint, the ten tiles
     * could each carry a different {@code asOf} and the screen would show two
     * different moments side by side while presenting them as one.
     *
     * <h2>An unknown key is omitted, not a 400</h2>
     *
     * <p>The single-widget route answers 404 for a key nothing implements, which
     * is right when that key is the whole request. It is wrong here: a batch that
     * fails entirely because one of ten keys is unimplemented turns a missing tile
     * into a blank dashboard. So unknown keys are dropped and the rest served —
     * every widget carries its own {@code key}, so a client matches on that and
     * can see what did not come back. Same degrade-per-tile principle
     * {@code unavailableReason} already applies to a role that cannot be served:
     * one tile explains itself, the page still renders.
     *
     * <p>Duplicates collapse and order is preserved, so a client asking twice for
     * one key pays once.
     */
    @Transactional(readOnly = true)
    RenderedBatch widgets(CallerIdentity caller, List<String> widgetKeys,
                          Long projectId, LocalDate from, LocalDate to) {
        List<String> keys = widgetKeys.stream()
                .filter(WidgetService::isImplemented)
                .distinct()
                .toList();

        Window window = window(from, to);
        DashboardScope scope = DashboardScope.of(caller);
        Instant asOf = summaries.computedAt(window.start(), window.end()).orElse(null);

        List<WidgetDtos.Widget> rendered = keys.stream()
                .map(key -> render(key, scope, projectId, window, asOf))
                .toList();

        // One validator for the whole set, over the keys actually SERVED. A
        // per-widget ETag cannot be reused — the client holds one response and so
        // can send only one If-None-Match — and hashing the keys as requested
        // rather than as served would let two genuinely different responses share
        // a validator whenever an unimplemented key was dropped.
        //
        // Null when any widget is unavailable, for the reason the single route
        // drops it too: availability turns on the caller's role, which no
        // summary-table timestamp can witness, so a 304 would outlive a promotion.
        boolean anyUnavailable = rendered.stream().anyMatch(w -> w.unavailableReason() != null);
        String etag = anyUnavailable
                ? null
                : etagOf(String.join(",", keys), scope, projectId, window.start(), window.end(), asOf);

        return new RenderedBatch(rendered, etag);
    }

    /** {@link #widgets}' return: the widgets served, and one validator for the set. */
    record RenderedBatch(List<WidgetDtos.Widget> widgets, String etag) {
    }

    /**
     * The resolved window. Extracted so {@link #widget} and {@link #widgets}
     * cannot drift on §S-05's default range — the reason {@link Rendered}'s note
     * gives for the controller not defaulting the dates itself applies twice as
     * hard now there are two entry points.
     */
    private record Window(LocalDate start, LocalDate end) {
    }

    private Window window(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_WINDOW_DAYS - 1L);
        return new Window(start, end);
    }

    /**
     * The switch, with the window and {@code asOf} already resolved. Both entry
     * points route through here, so a widget cannot be rendered one way singly
     * and another way in a batch.
     */
    private WidgetDtos.Widget render(String widgetKey, DashboardScope scope, Long projectId,
                                     Window window, Instant asOf) {
        // A-077 · before any branch, because every one of them would otherwise
        // answer with an empty series that reads as "this project has no
        // tickets". Placed here rather than in each widget for the reason the
        // switch exists at all: ten branches each remembering to check is ten
        // chances for the eleventh to forget, and the failure is silent.
        if (!scope.coversProject(projectId)) {
            return WidgetDtos.Widget.unavailable(widgetKey, NOT_YOUR_PROJECT);
        }

        LocalDate start = window.start();
        LocalDate end = window.end();
        return switch (widgetKey) {
            case "type-donut" -> typeDonut(scope, projectId, start, end, asOf);
            case "daily-stacked" -> dailyStacked(scope, projectId, start, end, asOf);
            case "velocity" -> velocity(scope, start, end, asOf);
            case "resource-load" -> resourceLoad(scope, start, end, asOf);
            case "priority-bar" -> priorityBar(scope, projectId, start, end, asOf);
            case "aging-buckets" -> agingBuckets(scope, projectId, start, end, asOf);
            case "calendar-heatmap" -> calendarHeatmap(scope, projectId, start, end, asOf);
            case "sla-gauge" -> slaGauge(scope, projectId, start, end, asOf);
            case "project-treemap" -> projectTreemap(scope, projectId, start, end, asOf);
            case "client-volume" -> clientVolume(scope, projectId, start, end, asOf);
            case "module-open" -> moduleOpen(scope, projectId, asOf);
            // A-058 · the four the ribbon unlocks. All read stage_daily_stats or
            // wip_by_stage, both filled by the worker from
            // ticket_stage_transitions — never from that table directly.
            case "stage-funnel" -> stageFunnel(scope, projectId, start, end, asOf);
            case "rework" -> rework(scope, projectId, start, end, asOf);
            case "stage-duration" -> stageDuration(scope, projectId, start, end, asOf);
            case "handoff-latency" -> handoffLatency(scope, projectId, start, end, asOf);
            default -> throw new IllegalStateException("implemented key with no branch: " + widgetKey);
        };
    }

    /**
     * The one sentence a delivery role sees in place of a chart.
     *
     * <p>Phrased as what the data cannot say rather than as a permission denial,
     * because it is not one — a Developer is not forbidden the task-type split,
     * there is no per-resource task-type split in existence to show them. The
     * difference matters to whoever reads it: one is a message to take to an
     * administrator, the other is a message to take to this backlog.
     */
    private static final String NO_RESOURCE_EQUIVALENT =
            "This breakdown is not kept per resource. Your dashboard reads the figures for "
                    + "tickets assigned to you, and those are recorded per person and per day "
                    + "without this split.";

    /**
     * Dashboard Rework Dev 2, PR 14 · before the worker has written a single day.
     *
     * <p>Distinct from an empty series, which draws as "no open tickets" — a
     * claim about the data rather than about the table, and a false one on a
     * database where the refresh has simply not run yet. module_daily_stats is
     * pure stock and does not backfill, so a new installation reads this
     * sentence until the first pass completes rather than a chart of nothing.
     */
    private static final String NOT_COMPUTED_YET =
            "Module figures have not been computed yet. The summary worker runs every five "
                    + "minutes, and these figures begin from the first pass rather than being "
                    + "backfilled.";

    /**
     * A-077 · the sentence for a project whose figures are not this caller's.
     *
     * <p>Distinct wording from {@link #NO_RESOURCE_EQUIVALENT} because it is a
     * different fact, and the difference is what the reader should do next. That
     * one says the data does not exist in this shape for anybody; this one says
     * the data exists and is somebody else's. One is a message for this backlog,
     * the other for whoever administers project membership.
     *
     * <p>Named for what it is rather than phrased as a denial, and it deliberately
     * does not say whether the project has many tickets or none — that is the
     * fact being withheld, and hinting at it in the refusal would give it away.
     */
    static final String NOT_YOUR_PROJECT =
            "These figures cover the projects you are a member of. This project is not one of "
                    + "them, so its numbers are not shown here.";

    // ── widget 7 · task type donut ───────────────────────────────────────────

    private WidgetDtos.Widget typeDonut(DashboardScope scope, Long projectId,
                                        LocalDate from, LocalDate to, Instant asOf) {
        if (scope.ownWorkOnly()) {
            return WidgetDtos.Widget.unavailable("type-donut", NO_RESOURCE_EQUIVALENT);
        }

        Map<Long, Long> counts = widgets.openByTaskType(from, to, scope.projectIds(), projectId);
        Map<Long, String> names = widgets.taskTypeNames();

        // Ordered by size, so the donut's largest slice is first and the legend
        // reads in the order the eye meets the arcs. A type with nothing open is
        // absent rather than a zero slice — the worker does not write it, and
        // drawing a zero-width arc would put a legend entry against no colour.
        List<WidgetDtos.Point> points = counts.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .map(entry -> WidgetDtos.Point.of(
                        names.getOrDefault(entry.getKey(), "Type #" + entry.getKey()),
                        entry.getValue(),
                        "/tickets?taskTypeId=" + entry.getKey() + "&excludeClosed=true"
                                + projectParam(projectId)))
                .toList();

        return WidgetDtos.Widget.of("type-donut", asOf,
                List.of(new WidgetDtos.Series("Open by task type", points)));
    }

    // ── widget 8 · daily created / closed / reopened ─────────────────────────

    private WidgetDtos.Widget dailyStacked(DashboardScope scope, Long projectId,
                                           LocalDate from, LocalDate to, Instant asOf) {
        if (scope.ownWorkOnly()) {
            // Not merely missing columns: created and reopened are not facts
            // about an assignee at all. A ticket is raised by a reporter and
            // reopened by a manager, and borrowing the project figures would
            // show a Developer their whole project's intake on a chart labelled
            // as theirs. DashboardRepository.resourceFlow refuses the same thing
            // for the same reason.
            return WidgetDtos.Widget.unavailable("daily-stacked", NO_RESOURCE_EQUIVALENT);
        }

        List<WidgetRepository.DailyFlow> days = widgets.dailyFlow(from, to, scope.projectIds(), projectId);

        List<WidgetDtos.Point> created = new ArrayList<>(days.size());
        List<WidgetDtos.Point> closed = new ArrayList<>(days.size());
        List<WidgetDtos.Point> reopened = new ArrayList<>(days.size());

        for (WidgetRepository.DailyFlow day : days) {
            String window = "reportedFrom=" + day.day() + "&reportedTo=" + day.day()
                    + projectParam(projectId);
            String label = day.day().toString();
            created.add(WidgetDtos.Point.of(label, day.created(), "/tickets?" + window));
            closed.add(WidgetDtos.Point.of(label, day.closed(),
                    "/tickets?status=CLOSED&closedFrom=" + day.day() + "&closedTo=" + day.day()
                            + projectParam(projectId)));
            reopened.add(WidgetDtos.Point.of(label, day.reopened(),
                    "/tickets?reopenedOnly=true&" + window));
        }

        // A day A-051 has not summarised is absent from all three series rather
        // than zero in them — the same rule A-055's sparklines follow. Plotting
        // the absence as zero draws a cliff to the axis and back on every
        // weekend and after every outage, and a stacked area's whole job is
        // shape.
        return WidgetDtos.Widget.of("daily-stacked", asOf, List.of(
                new WidgetDtos.Series("Created", created),
                new WidgetDtos.Series("Closed", closed),
                new WidgetDtos.Series("Reopened", reopened)));
    }

    // ── widget 9 · resource velocity ─────────────────────────────────────────

    private WidgetDtos.Widget velocity(DashboardScope scope, LocalDate from, LocalDate to, Instant asOf) {
        // Works for every role. A delivery role gets exactly one line — their
        // own — which is §S-05's developer variant, inherited rather than
        // re-decided in A-062.
        Long subject = scope.ownWorkOnly() ? scope.userId() : null;
        List<WidgetRepository.ResourceWeek> rows = widgets.velocityByWeek(from, to, scope.projectIds(), subject);

        // One series per resource, in the order the query returned them (by
        // name), so the legend is stable between loads. A LinkedHashMap rather
        // than groupingBy, which returns a HashMap and would reorder the legend
        // — and therefore the colour assignment — on every request.
        Map<Long, List<WidgetDtos.Point>> byResource = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();

        for (WidgetRepository.ResourceWeek row : rows) {
            names.putIfAbsent(row.userId(), row.resourceName());
            byResource.computeIfAbsent(row.userId(), id -> new ArrayList<>())
                    .add(WidgetDtos.Point.of(
                            row.weekStart().toString(),
                            row.closed(),
                            "/tickets?assigneeId=" + row.userId() + "&status=CLOSED"
                                    + "&closedFrom=" + row.weekStart()
                                    + "&closedTo=" + row.weekStart().plusDays(6)));
        }

        List<WidgetDtos.Series> series = byResource.entrySet().stream()
                .map(entry -> new WidgetDtos.Series(names.get(entry.getKey()), entry.getValue()))
                .toList();

        return WidgetDtos.Widget.of("velocity", asOf, series);
    }

    // ── widget 10 · resource load ────────────────────────────────────────────

    private WidgetDtos.Widget resourceLoad(DashboardScope scope, LocalDate from, LocalDate to, Instant asOf) {
        Long subject = scope.ownWorkOnly() ? scope.userId() : null;
        List<WidgetRepository.ResourceLoad> rows = widgets.resourceLoad(from, to, scope.projectIds(), subject);

        List<WidgetDtos.Point> waiting = new ArrayList<>(rows.size());
        List<WidgetDtos.Point> inProgress = new ArrayList<>(rows.size());
        List<WidgetDtos.Point> delayed = new ArrayList<>(rows.size());

        for (WidgetRepository.ResourceLoad row : rows) {
            String who = "/tickets?assigneeId=" + row.userId() + "&excludeClosed=true";
            waiting.add(new WidgetDtos.Point(row.resourceName(),
                    java.math.BigDecimal.valueOf(row.waiting()), who));
            inProgress.add(new WidgetDtos.Point(row.resourceName(),
                    java.math.BigDecimal.valueOf(row.inProgress()), who + "&status=IN_PROGRESS"));
            delayed.add(new WidgetDtos.Point(row.resourceName(),
                    java.math.BigDecimal.valueOf(row.delayed()), who + "&isDelayed=true"));
        }

        // Ordered worst-last so the delayed segment ends the bar, where its
        // length is read against the axis rather than against its neighbour.
        // The three partition the person's open load — see the migration; a
        // stacked bar makes an arithmetic claim and these three have to honour
        // it or the bar overstates what somebody is holding.
        return WidgetDtos.Widget.of("resource-load", asOf, List.of(
                new WidgetDtos.Series("Open", waiting),
                new WidgetDtos.Series("In progress", inProgress),
                new WidgetDtos.Series("Delayed", delayed)));
    }

    // ── widgets 11 & 12 · the stock breakdowns ───────────────────────────────

    private WidgetDtos.Widget priorityBar(DashboardScope scope, Long projectId,
                                          LocalDate from, LocalDate to, Instant asOf) {
        if (scope.ownWorkOnly()) {
            // resource_daily_stats carries assigned_critical and nothing for the
            // other three levels, so a four-bar chart would be one real bar and
            // three zeroes — which reads as "you have no low-priority work"
            // rather than "this is not recorded".
            return WidgetDtos.Widget.unavailable("priority-bar", NO_RESOURCE_EQUIVALENT);
        }

        WidgetRepository.StockBreakdown stock =
                widgets.stockBreakdown(from, to, scope.projectIds(), projectId).orElse(EMPTY_BREAKDOWN);

        // Ascending severity, left to right — the order §S-05 draws them and the
        // order the level tokens are declared in. A bar chart whose categories
        // reorder by value is unreadable across two loads.
        List<WidgetDtos.Point> points = List.of(
                level("Low", stock.openLow(), "LOW", projectId),
                level("Medium", stock.openMedium(), "MEDIUM", projectId),
                level("High", stock.openHigh(), "HIGH", projectId),
                level("Critical", stock.openCritical(), "CRITICAL", projectId));

        return WidgetDtos.Widget.of("priority-bar", asOf,
                List.of(new WidgetDtos.Series("Open by priority", points)));
    }

    private static WidgetDtos.Point level(String label, long value, String code, Long projectId) {
        return WidgetDtos.Point.of(label, value,
                "/tickets?level=" + code + "&excludeClosed=true" + projectParam(projectId));
    }

    private WidgetDtos.Widget agingBuckets(DashboardScope scope, Long projectId,
                                           LocalDate from, LocalDate to, Instant asOf) {
        if (scope.ownWorkOnly()) {
            // A-062 · answered now. A-056 refused this because
            // resource_daily_stats had no aging columns and said the schema
            // change belonged to the task that needed it; V20260817_1130 adds
            // them with the project table's own edges, so the two charts stay
            // comparable.
            return resourceAging(scope, from, to, asOf);
        }

        WidgetRepository.StockBreakdown stock =
                widgets.stockBreakdown(from, to, scope.projectIds(), projectId).orElse(EMPTY_BREAKDOWN);

        // The labels are the schema's edges, not §S-05's. The blueprint draws
        // 0–2 / 3–5 / 6–10 / >10; A-050 stored 0–2 / 3–7 / 8–30 / 31+ and fixed
        // them in the column deliberately, so that a bucket boundary cannot move
        // between two loads of the same day. Labelling them the blueprint's way
        // over the schema's numbers would be the one genuinely dishonest option.
        //
        // A-060 · these four now deep-link, and **no age filter was needed**.
        //
        // A-056 left them dead because §S-05 asks to drill into an "age range"
        // and the list had nothing that expressed one. But age here is not an
        // independent quantity: the worker derives every bucket from
        // DATEDIFF(day, date_reported), so "open 3 to 7 days" *is* "still open,
        // reported between day-7 and day-3". The reported-date window this task
        // added for the date-scoped cards therefore answers the aging buckets
        // as well, and a second parameter meaning the same thing in different
        // units would have been two ways to ask one question — and two places
        // for the bucket edges to disagree with the worker's.
        //
        // Anchored on `to`, the day the figures were computed for, not on
        // today: the bar was measured at the end of that day, and resolving the
        // window against a clock that has moved since would open a list that
        // no longer matches the bar which was clicked.
        List<WidgetDtos.Point> points = List.of(
                agingBucket("0–2 days", stock.aging02(), to, 0, 2, projectId, ""),
                agingBucket("3–7 days", stock.aging37(), to, 3, 7, projectId, ""),
                agingBucket("8–30 days", stock.aging830(), to, 8, 30, projectId, ""),
                // The open-ended bucket has no lower bound on the reported date
                // — "31 days or older" is everything up to day-31, with nothing
                // on the far side. Passing a `maxDays` of null rather than an
                // arbitrary large number, so the URL says what it means.
                agingBucket("31+ days", stock.aging31Plus(), to, 31, null, projectId, ""));

        return WidgetDtos.Widget.of("aging-buckets", asOf,
                List.of(new WidgetDtos.Series("Open by age", points)));
    }

    /**
     * A-062 · widget 12 for a delivery role — the same four bars, their own work.
     *
     * <h2>Why this is a branch and not a second widget</h2>
     *
     * <p>It answers the same question with the same four buckets and the same
     * drill-down convention; only the table differs. A second widget key would
     * have given the frontend a role decision to make about which one to ask
     * for — a second statement of the rule {@link DashboardScope} exists to
     * state once, and one that could disagree with the server's.
     *
     * <p>The bars are anchored on the day the row was <em>measured</em>, which
     * comes back from the query rather than being assumed to be {@code to}. For
     * the project table {@code to} is a fair approximation because every project
     * gets a row every day; here a person only earns a row on days they held or
     * did something, so the latest summarised day for one resource can be
     * several days behind the window's end — and the aging drill-downs are built
     * by subtracting from that day. Getting it from the row is the only way the
     * link and the bar can be about the same date.
     */
    private WidgetDtos.Widget resourceAging(DashboardScope scope, LocalDate from, LocalDate to,
                                            Instant asOf) {
        Optional<WidgetRepository.ResourceAging> row =
                widgets.resourceAging(from, to, scope.userId());

        if (row.isEmpty()) {
            // Nothing summarised for this person in the window. An empty series
            // renders as "nothing to show for this filter and date range",
            // which is the honest reading — four zero-height bars would claim
            // they hold no open work at all.
            return WidgetDtos.Widget.of("aging-buckets", asOf,
                    List.of(new WidgetDtos.Series("Open by age", List.of())));
        }

        WidgetRepository.ResourceAging aging = row.get();
        LocalDate measured = aging.measuredOn();
        String mine = "&assigneeId=" + scope.userId();

        List<WidgetDtos.Point> points = List.of(
                agingBucket("0–2 days", aging.aging02(), measured, 0, 2, null, mine),
                agingBucket("3–7 days", aging.aging37(), measured, 3, 7, null, mine),
                agingBucket("8–30 days", aging.aging830(), measured, 8, 30, null, mine),
                agingBucket("31+ days", aging.aging31Plus(), measured, 31, null, null, mine));

        return WidgetDtos.Widget.of("aging-buckets", asOf,
                List.of(new WidgetDtos.Series("Open by age", points)));
    }

    /**
     * One aging bar, with its age range expressed as the reported-date window
     * that produced it.
     *
     * <p>The arithmetic inverts the worker's. It counts a ticket into the
     * {@code minDays}–{@code maxDays} bucket when
     * {@code DATEDIFF(asOfDay, DATE(date_reported))} falls in that range, so the
     * tickets in the bar are exactly those reported between
     * {@code asOfDay - maxDays} and {@code asOfDay - minDays}. The older edge of
     * the bucket is the <em>earlier</em> date, which is the inversion worth
     * reading twice — getting it backwards yields a link that looks right and
     * opens the opposite end of the distribution.
     *
     * @param maxDays null for the open-ended oldest bucket, which has no
     *                earliest reported date to bound it.
     * @param extra   A-062 · already-formed extra parameters, for the resource
     *                variant's {@code &assigneeId=}. Empty for the project bars.
     *                Appended rather than given its own branch so both roles'
     *                links are built by one method — the inversion below is the
     *                part that must not be reimplemented twice.
     */
    private static WidgetDtos.Point agingBucket(String label, long value, LocalDate asOfDay,
                                                int minDays, Integer maxDays, Long projectId,
                                                String extra) {
        StringBuilder link = new StringBuilder("/tickets?excludeClosed=true");
        if (maxDays != null) {
            link.append("&reportedFrom=").append(asOfDay.minusDays(maxDays));
        }
        link.append("&reportedTo=").append(asOfDay.minusDays(minDays));
        link.append(projectParam(projectId));
        link.append(extra);

        return WidgetDtos.Point.of(label, value, link.toString());
    }

    // ── widget 13 · calendar heatmap ─────────────────────────────────────────

    /**
     * A-057 · one cell per day, intensity by that day's activity.
     *
     * <h2>The one widget whose measure changes with the role, on purpose</h2>
     *
     * <p>§S-05 calls this the "date-wise report" and the project-keyed answer is
     * tickets <b>created</b> per day. A delivery role has no such figure — intake
     * is not a fact about an assignee — but they do have one that answers the
     * same question about their own work: tickets they <b>closed</b> per day.
     *
     * <p>So this returns a series either way and the <em>series name</em> carries
     * the difference, rather than refusing the widget as the other four do. That
     * is a deliberate departure and worth the sentence: the alternative was
     * showing a Developer a blank panel where a perfectly good answer exists, or
     * — far worse — quietly plotting their project's intake under their own
     * heading. The frontend renders the series name as the legend, so what is
     * being counted is on the screen and not only in this javadoc.
     */
    private WidgetDtos.Widget calendarHeatmap(DashboardScope scope, Long projectId,
                                              LocalDate from, LocalDate to, Instant asOf) {
        List<WidgetDtos.Point> points;
        String seriesName;

        if (scope.ownWorkOnly()) {
            seriesName = "Tickets you closed";
            points = widgets.resourceDailyClosed(from, to, scope.userId()).stream()
                    .map(day -> WidgetDtos.Point.of(
                            day.day().toString(), day.closed(),
                            "/tickets?assigneeId=" + scope.userId() + "&status=CLOSED"
                                    + "&closedFrom=" + day.day() + "&closedTo=" + day.day()))
                    .toList();
        } else {
            seriesName = "Tickets created";
            points = widgets.dailyFlow(from, to, scope.projectIds(), projectId).stream()
                    .map(day -> WidgetDtos.Point.of(
                            day.day().toString(), day.created(),
                            "/tickets?reportedFrom=" + day.day() + "&reportedTo=" + day.day()
                                    + projectParam(projectId)))
                    .toList();
        }

        // Unsummarised days stay absent rather than becoming zero-activity
        // cells. On a heatmap the two are visually identical — an empty square
        // either way — but one says "a quiet day" and the other says "we did
        // not look". The frontend draws absent days as gaps in the grid.
        return WidgetDtos.Widget.of("calendar-heatmap", asOf,
                List.of(new WidgetDtos.Series(seriesName, points)));
    }

    // ── widget 14 · SLA compliance gauge ─────────────────────────────────────

    /**
     * A-057 · the share of finished work that landed on time.
     *
     * <p>Two series rather than a single percentage, because a gauge that shows
     * only a ratio hides its own sample size: 100% off two tickets and 100% off
     * two hundred are the same needle and very different facts. "Met" and
     * "Breached" are returned as counts and the client draws the arc from them,
     * so the figures behind the percentage are always available — in the
     * tooltip, and in the hidden data table for anybody not looking at it.
     */
    private WidgetDtos.Widget slaGauge(DashboardScope scope, Long projectId,
                                       LocalDate from, LocalDate to, Instant asOf) {
        if (scope.ownWorkOnly()) {
            // resource_daily_stats records no SLA outcome. Borrowing the project
            // figures would show a Developer their whole project's compliance
            // under a heading they would reasonably read as their own.
            return WidgetDtos.Widget.unavailable("sla-gauge", NO_RESOURCE_EQUIVALENT);
        }

        Optional<WidgetRepository.SlaCompliance> compliance =
                widgets.slaCompliance(from, to, scope.projectIds(), projectId);

        if (compliance.isEmpty()) {
            // Nothing computed for this window at all — distinct from "nothing
            // closed", which is a real measurement of zero. An empty series
            // renders as "nothing to show" rather than as a needle at 0%, which
            // would read as total failure.
            return WidgetDtos.Widget.of("sla-gauge", asOf,
                    List.of(new WidgetDtos.Series("SLA compliance", List.of())));
        }

        WidgetRepository.SlaCompliance sla = compliance.get();
        long breached = sla.closed() - sla.met();
        String window = "reportedFrom=" + from + "&reportedTo=" + to + projectParam(projectId);

        return WidgetDtos.Widget.of("sla-gauge", asOf, List.of(
                new WidgetDtos.Series("Met", List.of(WidgetDtos.Point.of(
                        "Met", sla.met(),
                        // No filter expresses "closed within its due date", so
                        // the met half opens the closed list for the window and
                        // no more. Claiming a narrower filter than the list can
                        // apply is the failure mode A-056's aging buckets chose
                        // a null link over.
                        "/tickets?status=CLOSED&" + window))),
                new WidgetDtos.Series("Breached", List.of(WidgetDtos.Point.of(
                        "Breached", breached,
                        // This half *is* expressible: still-open overdue work is
                        // exactly isDelayed, and §S-05's drill-down column asks
                        // for the "breached list".
                        "/tickets?isDelayed=true&" + window)))));
    }

    // ── widget 15 · project treemap ──────────────────────────────────────────

    /**
     * A-057 · open tickets per project, sized by share.
     *
     * <p>Unavailable to a delivery role, and this one is not about missing
     * columns so much as a missing question. {@code resource_daily_stats} has no
     * project dimension at all, and "how is my work spread across projects" is
     * not what §S-05 asks widget 15 — it asks for the organisation's
     * distribution, which is a manager's view by construction.
     */
    private WidgetDtos.Widget projectTreemap(DashboardScope scope, Long projectId,
                                             LocalDate from, LocalDate to, Instant asOf) {
        if (scope.ownWorkOnly()) {
            return WidgetDtos.Widget.unavailable("project-treemap", NO_RESOURCE_EQUIVALENT);
        }

        List<WidgetDtos.Point> points =
                widgets.projectDistribution(from, to, scope.projectIds(), projectId).stream()
                        .map(share -> WidgetDtos.Point.of(
                                share.projectName(),
                                share.openTotal(),
                                "/tickets?projectId=" + share.projectId() + "&excludeClosed=true"))
                        .toList();

        return WidgetDtos.Widget.of("project-treemap", asOf,
                List.of(new WidgetDtos.Series("Open by project", points)));
    }

    // ── widget 20 · client-wise volume ───────────────────────────────────────

    /**
     * How many named clients the bar chart draws before the rest are pooled.
     *
     * <p>Twelve is a readability limit, not a data one — a horizontal bar chart
     * puts one label per row down the y axis, and at the frame's fixed height a
     * fortieth row is four pixels tall with a name it cannot show. Every other
     * widget's categories are bounded by something real (four levels, four age
     * buckets, the task-type master), and clients are the first dimension here
     * that grows without limit.
     */
    private static final int TOP_CLIENTS = 12;

    /**
     * A-059 · §S-05 widget 20 — tickets raised per client, largest first.
     *
     * <h2>Volume is intake, and it is summed</h2>
     *
     * <p>Every other categorical widget on this screen reads the latest
     * summarised day, because open/critical/aging are stock and summing stock
     * over days counts the same ticket once per day. This one is the exception
     * and it is deliberate: blueprint §7.8 lists the client report's columns as
     * "Volume, open versus closed, SLA compliance…", which puts volume and
     * open-versus-closed side by side as different measures. Volume is what was
     * <em>raised</em>, so it is flow, and flow is what a date window is for —
     * a client that raised three tickets a day for a month raised ninety, not
     * three.
     *
     * <p>Widget 15's treemap sits two panels away doing the opposite for the
     * opposite reason. Both carry the argument, because the pair is exactly
     * where a later reader would "fix" one to match the other.
     *
     * <h2>The long tail is pooled, never dropped</h2>
     *
     * <p>Beyond {@link #TOP_CLIENTS} the remainder becomes a single
     * "Other (N clients)" bar rather than being truncated away. A chart that
     * silently showed the top twelve would be read as the whole picture — the
     * bars are the only figures on screen, and nothing in a bar chart says
     * "there were also thirty-one more". Pooling keeps the arithmetic true: the
     * bars still sum to every client-raised ticket in the window.
     *
     * <p><b>The pooled bar has no drill-down</b>, and that is the honest
     * outcome rather than a gap: {@code /tickets?clientId=} takes one id, and
     * there is no filter expressing "any of these thirty-one clients". A-056's
     * aging buckets set the precedent — a segment with no target does nothing
     * on click rather than opening a list that contradicts what was clicked.
     */
    private WidgetDtos.Widget clientVolume(DashboardScope scope, Long projectId,
                                           LocalDate from, LocalDate to, Instant asOf) {
        if (scope.ownWorkOnly()) {
            // Not a permission refusal, and not merely a missing column either:
            // client_daily_stats is keyed by project and client, and a delivery
            // role's dashboard reads figures keyed by person. "Which clients
            // raised the tickets assigned to me" is a question nothing records,
            // and borrowing the project figures would head a chart of the
            // organisation's client mix as though it were theirs — the same
            // trap dailyStacked names above.
            return WidgetDtos.Widget.unavailable("client-volume", NO_RESOURCE_EQUIVALENT);
        }

        List<WidgetRepository.ClientVolume> clients =
                widgets.clientVolume(from, to, scope.projectIds(), projectId);

        // The window travels with every link, so the list opens on the same
        // tickets the bar counted. reportedFrom/reportedTo rather than a
        // created-date parameter of its own: A-060 established that pair as the
        // one convention for "raised between", and the aging buckets and the
        // heatmap already emit it.
        String window = "&reportedFrom=" + from + "&reportedTo=" + to + projectParam(projectId);

        List<WidgetDtos.Point> points = new ArrayList<>(Math.min(clients.size(), TOP_CLIENTS + 1));
        for (WidgetRepository.ClientVolume client : clients.stream().limit(TOP_CLIENTS).toList()) {
            points.add(WidgetDtos.Point.of(
                    client.clientName(),
                    client.created(),
                    "/tickets?clientId=" + client.clientId() + window));
        }

        if (clients.size() > TOP_CLIENTS) {
            List<WidgetRepository.ClientVolume> tail = clients.subList(TOP_CLIENTS, clients.size());
            long pooled = tail.stream().mapToLong(WidgetRepository.ClientVolume::created).sum();
            // The count is in the label because it is the part that cannot be
            // read off the chart: one bar of 47 tickets could be two clients or
            // forty, and the difference changes what the bar means.
            points.add(new WidgetDtos.Point(
                    "Other (" + tail.size() + " clients)", java.math.BigDecimal.valueOf(pooled), null));
        }

        return WidgetDtos.Widget.of("client-volume", asOf,
                List.of(new WidgetDtos.Series("Tickets raised", points)));
    }

    /**
     * Dashboard Rework Dev 2, PR 14 · module-wise total open tickets.
     *
     * <h2>Three series, and they partition</h2>
     *
     * <p>A stacked bar makes an arithmetic claim, so the three segments are
     * disjoint and add up to the module's open total. The partition is made in
     * {@code DailyStatsRepository.refreshModuleStats} rather than here — one
     * {@code CASE} per ticket with overdue tested first — so an overdue WIP
     * ticket is counted once and the segments cannot drift apart from the
     * figure they are supposed to sum to.
     *
     * <p>Ordered overdue-last so the segment that needs reading against the
     * axis ends the bar, which is {@code resourceLoad}'s reasoning applied to
     * a different triple.
     *
     * <h2>One day, not the window</h2>
     *
     * <p>Every column is stock. Summing a date range would count a ticket that
     * stayed open all week five times, so this reads the latest computed day
     * and ignores {@code from}/{@code to} entirely. Stated in the drill-downs
     * too: they carry no date window, because the figure has none.
     *
     * <h2>Delivery roles have no module figures</h2>
     *
     * <p>{@code module_daily_stats} is keyed by project and module; a delivery
     * role's dashboard reads figures keyed by person. "Which modules are the
     * tickets assigned to me in" is answerable, but not from this table, and
     * borrowing the project figures would head a chart of the organisation's
     * module mix as though it were theirs — the trap {@code clientVolume} and
     * {@code dailyStacked} both name.
     */
    private WidgetDtos.Widget moduleOpen(DashboardScope scope, Long projectId, Instant asOf) {
        if (scope.ownWorkOnly()) {
            return WidgetDtos.Widget.unavailable("module-open", NO_RESOURCE_EQUIVALENT);
        }

        LocalDate day = widgets.latestModuleStatDate(scope.projectIds());
        if (day == null) {
            // Before the worker's first pass there is no day to read. An empty
            // series would draw as "no open tickets", which is a claim about
            // the data rather than about the table, and a false one.
            return WidgetDtos.Widget.unavailable("module-open", NOT_COMPUTED_YET);
        }

        List<WidgetRepository.ModuleOpen> rows =
                widgets.moduleOpen(day, scope.projectIds(), projectId);

        List<WidgetDtos.Point> notStarted = new ArrayList<>(rows.size());
        List<WidgetDtos.Point> wip = new ArrayList<>(rows.size());
        List<WidgetDtos.Point> overdue = new ArrayList<>(rows.size());

        for (WidgetRepository.ModuleOpen row : rows) {
            // Only parameters GET /tickets implements — statusCategory and
            // moduleId both landed before this widget did, and
            // DrillDownContractTest fails the build on anything else. No date
            // window: the figure is stock at one moment, and reportedFrom/To
            // would narrow the list to tickets *raised* in a window the bar
            // never counted by.
            String where = "/tickets?moduleId=" + row.moduleId() + "&excludeClosed=true"
                    + projectParam(projectId);
            notStarted.add(new WidgetDtos.Point(row.moduleName(),
                    java.math.BigDecimal.valueOf(row.notStarted()), where + "&statusCategory=TODO"));
            wip.add(new WidgetDtos.Point(row.moduleName(),
                    java.math.BigDecimal.valueOf(row.wip()), where + "&statusCategory=IN_PROGRESS"));
            // The overdue segment is the one whose drill-down cannot be exact.
            // isDelayed is the ticket's own flag; the segment counts
            // planned_close_date in the past, and the SLA scanner sets the flag
            // from the same date, so the two agree in every case the scanner has
            // seen. A ticket that went overdue since the last scan is in the bar
            // and not yet in the list — a lag, not a disagreement, and the
            // alternative is a segment nobody can click.
            overdue.add(new WidgetDtos.Point(row.moduleName(),
                    java.math.BigDecimal.valueOf(row.overdue()), where + "&isDelayed=true"));
        }

        return WidgetDtos.Widget.of("module-open", asOf, List.of(
                new WidgetDtos.Series("Not started", notStarted),
                new WidgetDtos.Series("WIP", wip),
                new WidgetDtos.Series("Overdue", overdue)));
    }

    private static final WidgetRepository.StockBreakdown EMPTY_BREAKDOWN =
            new WidgetRepository.StockBreakdown(0, 0, 0, 0, 0, 0, 0, 0);

    // ── A-058 · widgets 16–19, the four the ribbon unlocks ───────────────────

    /**
     * The sentence all four of A-058's widgets show when the ribbon has recorded
     * nothing in the caller's scope.
     *
     * <p>Not an empty chart and not a zero. On these four the two are the same
     * picture and opposite facts: an empty funnel says no work is queued, and a
     * rework card reading 0 says no ticket has ever been sent back. Both are
     * claims about how a team works, made from a table nobody has written to.
     *
     * <p>A-068's first-time-right showed 100% for precisely this reason and is
     * now withheld the same way; A-057's SLA gauge established the form —
     * <b>nothing measured renders as a sentence, never as a needle at zero</b>.
     *
     * <p>Worded as what has not been recorded rather than as a permission or a
     * missing feature, because that is what a reader can act on: the ribbon is
     * a thing their team either uses or does not, and the message points at
     * that rather than at an administrator or at this backlog.
     */
    static final String RIBBON_NOT_IN_USE =
            "The Workflow Ribbon has not recorded any stage movement for the projects you can see, "
                    + "so there is nothing to measure yet. Stage figures appear once tickets start "
                    + "moving between stages.";

    /**
     * The sentence a delivery role sees in place of these four.
     *
     * <p>Distinct from {@link #NO_RESOURCE_EQUIVALENT}, which says a breakdown
     * is not kept per resource. Here the figures exist per resource in
     * principle — a Developer's own tickets do move between stages — and what
     * is missing is that {@code stage_daily_stats} and {@code wip_by_stage} are
     * both keyed by project, exactly as §S-05 intends: it names widgets 1–6, 9
     * and 12 as the Developer's dashboard and these four are a manager's view of
     * where a team's work piles up.
     */
    private static final String STAGE_VIEW_IS_A_MANAGER_VIEW =
            "Stage flow is measured per project rather than per person. Your dashboard reads the "
                    + "figures for tickets assigned to you, and where work queues across a team is "
                    + "not a question those figures can answer.";

    /**
     * A-058 · §S-05 widget 16 — how many tickets sit in each ribbon stage.
     *
     * <h2>Stock, so the latest summarised day and never the window summed</h2>
     *
     * <p>The trap is that summing looks right: a funnel drawn from a fortnight
     * of daily snapshots has the correct <em>proportions</em> and fourteen times
     * the height, and proportions are what a funnel is read for. Widget 7's
     * donut carries the same argument and widget 20's bar deliberately does the
     * opposite, which is why all three say so.
     *
     * <h2>Ordered by ribbon position, not by size</h2>
     *
     * <p>Unlike the donut and the client bars. A funnel <em>is</em> the
     * sequence — §4A.8 describes it as "spot the bottleneck instantly", and a
     * bottleneck is only visible as a bulge at a known point in a known order.
     * Sorting by count would produce the same numbers arranged so that the one
     * thing the chart exists to show cannot be seen.
     *
     * <p>A stage with nothing in it is still drawn, and that is the difference
     * from the donut: an empty band in the middle of a funnel is information —
     * work is arriving after it and not sitting there — whereas an empty slice
     * of a donut is a legend entry against no colour. A stage the master no
     * longer defines still appears, under its own code, so retiring a stage
     * cannot hide the tickets standing in it.
     */
    private WidgetDtos.Widget stageFunnel(DashboardScope scope, Long projectId,
                                          LocalDate from, LocalDate to, Instant asOf) {
        if (scope.ownWorkOnly()) {
            return WidgetDtos.Widget.unavailable("stage-funnel", STAGE_VIEW_IS_A_MANAGER_VIEW);
        }
        if (!widgets.ribbonHasData(scope.projectIds(), projectId)) {
            return WidgetDtos.Widget.unavailable("stage-funnel", RIBBON_NOT_IN_USE);
        }

        Map<String, Long> wip = widgets.openByStage(from, to, scope.projectIds(), projectId);
        Map<String, String> names = widgets.stageNames();

        // The master's order first, then any code the master no longer knows —
        // appended rather than dropped, because a retired stage still holding
        // tickets is the case somebody most needs to see.
        List<String> ordered = new ArrayList<>(names.keySet());
        wip.keySet().stream().filter(stage -> !names.containsKey(stage)).forEach(ordered::add);

        List<WidgetDtos.Point> points = ordered.stream()
                .map(stage -> WidgetDtos.Point.of(
                        names.getOrDefault(stage, stage),
                        wip.getOrDefault(stage, 0L),
                        // The one drill-down of the four that names exactly the
                        // population it counted: `stage=` filters on
                        // tickets.current_stage and the bar counts tickets
                        // currently at that stage. excludeClosed matches the
                        // worker's own predicate, so the list and the band agree.
                        "/tickets?stage=" + stage + "&excludeClosed=true" + projectParam(projectId)))
                .toList();

        return WidgetDtos.Widget.of("stage-funnel", asOf,
                List.of(new WidgetDtos.Series("Tickets in stage", points)));
    }

    /**
     * A-058 · §S-05 widget 17 — open tickets being reworked, and the ping-pong
     * subset.
     *
     * <h2>Two counts and a denominator, because one number is unreadable</h2>
     *
     * <p>Twelve tickets in rework is a crisis in a team holding twenty and a
     * rounding error in two thousand. The open total travels with the counts
     * from the same summarised row, so the widget cannot show a numerator from
     * one day beside a denominator from another.
     *
     * <p>§7.9 puts this widget at {@code iteration_no >= 2} and §4A.7 raises the
     * ping-pong alert at {@code >= 3}. Both are drawn: a team with ten one-off
     * corrections and a team with ten tickets in a loop produce the same single
     * figure and want different conversations.
     *
     * <h2>⚠️ No drill-down, and this is a real gap rather than a decision</h2>
     *
     * <p>{@code GET /tickets} has no iteration filter — the closest is
     * {@code reopenedOnly}, which is {@code cycle_no} and <b>not</b>
     * {@code iteration_no}. The baseline migration calls those two "the single
     * most misread concept in the spec", and linking a rework card to a reopened
     * list would be that exact misreading shipped as a feature: a ticket
     * reopened three times and resolved cleanly each time is not being reworked.
     *
     * <p>So the segments carry no target, following A-056's aging buckets and
     * A-059's pooled bar — a segment with no honest filter does nothing on click
     * rather than opening a list that contradicts what was clicked. <b>The
     * missing parameter is Stream C's</b>: {@code GET /tickets} wants a
     * {@code minIteration=} to express it, and A-060 is the precedent for how
     * that lands — the drill-downs existed for three tasks before the list grew
     * the parameter that made them true.
     */
    private WidgetDtos.Widget rework(DashboardScope scope, Long projectId,
                                     LocalDate from, LocalDate to, Instant asOf) {
        if (scope.ownWorkOnly()) {
            return WidgetDtos.Widget.unavailable("rework", STAGE_VIEW_IS_A_MANAGER_VIEW);
        }
        if (!widgets.ribbonHasData(scope.projectIds(), projectId)) {
            return WidgetDtos.Widget.unavailable("rework", RIBBON_NOT_IN_USE);
        }

        Optional<WidgetRepository.ReworkCounts> counts =
                widgets.reworkCounts(from, to, scope.projectIds(), projectId);
        if (counts.isEmpty()) {
            // No summarised day in the window. Distinct from "nothing is in
            // rework", which is what a row of zeroes would have said.
            return WidgetDtos.Widget.unavailable("rework", RIBBON_NOT_IN_USE);
        }

        WidgetRepository.ReworkCounts row = counts.get();
        List<WidgetDtos.Point> points = List.of(
                WidgetDtos.Point.of("Reworked (2 or more passes)", row.rework(), null),
                WidgetDtos.Point.of("Ping-pong (3 or more passes)", row.pingpong(), null),
                // The remainder rather than the total, so "First pass" and
                // "Reworked" partition the open backlog and the client can draw
                // a share without subtracting.
                //
                // 🔴 Ping-pong is NOT a third part of that partition — it is a
                // subset of Reworked, counted again at a higher threshold.
                // Stacking all three would count every ping-pong ticket twice
                // and produce a bar longer than the backlog it describes. The
                // client draws two segments and nests the third; this comment
                // is here because the three points arriving in one series is
                // exactly what invites stacking them.
                //
                // Floored at zero: rework is counted over the same open
                // population as open_total, so the subtraction cannot go
                // negative unless the two columns were computed from different
                // days — which the single-row read prevents, and which this
                // guards against rather than renders as a negative bar.
                WidgetDtos.Point.of("First pass", Math.max(0, row.openTotal() - row.rework()), null));

        return WidgetDtos.Widget.of("rework", asOf,
                List.of(new WidgetDtos.Series("Open tickets by rework", points)));
    }

    /**
     * A-058 · §S-05 widget 18 — average time per stage, split into work and
     * waiting.
     *
     * <h2>The split is the widget</h2>
     *
     * <p>§4A.8 asks "where the calendar time actually goes, split into active vs
     * idle". A stage averaging four days of which three hours were worked is not
     * slow because the work is hard, and the total on its own cannot tell those
     * apart. Idle is the remainder — elapsed minus what somebody logged — and it
     * is computed here rather than stored, so the two halves cannot drift.
     *
     * <p><b>Idle is floored at zero and that hides nothing.</b> Effort attaches
     * to a stage code rather than to a visit, so a stage entered twice on a
     * rework loop has both visits' hours counted against whichever of them
     * sealed in the window — active can exceed elapsed for that stage, and a
     * negative idle band would draw a bar below the axis for what is really an
     * attribution limit. The active share is therefore an upper bound and idle a
     * lower one, which is the safe direction for a chart whose purpose is
     * finding queue waste: it under-claims waste rather than inventing it. The
     * migration and A-067's report both state the same limit.
     *
     * <h2>Hours, divided once, at the end</h2>
     *
     * <p>The table stores minutes and this divides by 60 after summing. Dividing
     * per day and averaging the results would weight a day with one sealed visit
     * equally with a day with fifty.
     */
    private WidgetDtos.Widget stageDuration(DashboardScope scope, Long projectId,
                                            LocalDate from, LocalDate to, Instant asOf) {
        if (scope.ownWorkOnly()) {
            return WidgetDtos.Widget.unavailable("stage-duration", STAGE_VIEW_IS_A_MANAGER_VIEW);
        }
        if (!widgets.ribbonHasData(scope.projectIds(), projectId)) {
            return WidgetDtos.Widget.unavailable("stage-duration", RIBBON_NOT_IN_USE);
        }

        List<WidgetRepository.StageDuration> stages =
                widgets.stageDurations(from, to, scope.projectIds(), projectId);
        Map<String, String> names = widgets.stageNames();

        // Ribbon order, like the funnel and unlike the donut: reading "QA is
        // where it sits" off a bar chart depends on knowing where QA comes.
        List<String> order = new ArrayList<>(names.keySet());
        List<WidgetRepository.StageDuration> ordered = stages.stream()
                .sorted(Comparator.comparingInt(stage -> {
                    int at = order.indexOf(stage.stageCode());
                    return at < 0 ? Integer.MAX_VALUE : at;
                }))
                .toList();

        List<WidgetDtos.Point> active = new ArrayList<>(ordered.size());
        List<WidgetDtos.Point> idle = new ArrayList<>(ordered.size());

        for (WidgetRepository.StageDuration stage : ordered) {
            String label = names.getOrDefault(stage.stageCode(), stage.stageCode());
            // The bar is a DURATION, so a list opening on a different population
            // cannot contradict it — there is no count on screen to disagree
            // with. That is what separates this from widget 17, where the
            // number on the card is a ticket count and a mismatched list would
            // be read as the card being wrong. The list opens on tickets
            // standing in the stage now; the bar measures visits that ended in
            // the window.
            String drillDown = "/tickets?stage=" + stage.stageCode() + "&excludeClosed=true"
                    + projectParam(projectId);

            long activeMins = Math.min(stage.activeMins(), stage.elapsedMins());
            long idleMins = stage.elapsedMins() - activeMins;

            active.add(new WidgetDtos.Point(label, averageHours(activeMins, stage.visits()), drillDown));
            idle.add(new WidgetDtos.Point(label, averageHours(idleMins, stage.visits()), drillDown));
        }

        return WidgetDtos.Widget.of("stage-duration", asOf, List.of(
                new WidgetDtos.Series("Active", active),
                new WidgetDtos.Series("Idle", idle)));
    }

    /**
     * A-058 · §S-05 widget 19 — handoff latency, as a trend.
     *
     * <h2>Pure queue waste, and the only figure here nobody owns</h2>
     *
     * <p>§7.6 defines it as the gap between one stage being left and the next
     * being entered. It belongs to neither stage's duration and appears in no
     * other figure on this dashboard — a team can look fully efficient on widget
     * 18 while tickets spend days between them.
     *
     * <p>Drawn as a trend rather than per stage because §7.9 asks for one and
     * because the useful question is whether it is growing. The per-stage cut is
     * A-067's stage-cycle-time report.
     *
     * <p><b>Calendar minutes, from the worker.</b> A Friday-evening handoff
     * picked up at nine on Monday is a few minutes of queue waste, not two days
     * of it, and this is the one duration in the schema nothing had already
     * corrected — see {@code DailyStatsRepository.applyHandoffLatency}.
     *
     * <h2>⚠️ No drill-down, because a handoff is not a ticket</h2>
     *
     * <p>§7.9 gives this widget's drill-down as "slowest handoffs", which is a
     * list of <em>hops</em>. {@code GET /tickets} lists tickets and has no
     * parameter for "had a handoff on this date"; the nearest,
     * {@code reportedFrom}/{@code reportedTo}, filters on when a ticket was
     * raised, which is a different set that would look plausible and be wrong.
     * A-060's defect was exactly a link whose filter did not mean what the
     * segment meant, so this emits nothing rather than something close.
     */
    private WidgetDtos.Widget handoffLatency(DashboardScope scope, Long projectId,
                                             LocalDate from, LocalDate to, Instant asOf) {
        if (scope.ownWorkOnly()) {
            return WidgetDtos.Widget.unavailable("handoff-latency", STAGE_VIEW_IS_A_MANAGER_VIEW);
        }
        if (!widgets.ribbonHasData(scope.projectIds(), projectId)) {
            return WidgetDtos.Widget.unavailable("handoff-latency", RIBBON_NOT_IN_USE);
        }

        List<WidgetDtos.Point> points = widgets
                .handoffLatency(from, to, scope.projectIds(), projectId).stream()
                .map(day -> new WidgetDtos.Point(
                        day.day().toString(), averageHours(day.minutes(), day.handoffs()), null))
                .toList();

        return WidgetDtos.Widget.of("handoff-latency", asOf,
                List.of(new WidgetDtos.Series("Average handoff wait (hours)", points)));
    }

    /**
     * Minutes to average hours, two decimals.
     *
     * <p>{@code HALF_UP} and two places to match
     * {@code WorkingHoursService.workingHoursBetween}, which is where the
     * handoff half of these minutes came from — rounding the same quantity two
     * different ways at two ends of the same pipeline is how a total stops
     * matching its parts.
     *
     * <p>A zero denominator answers zero rather than throwing. The queries
     * already exclude those rows with {@code HAVING}, so this is a backstop and
     * not a live path; it returns a number because the alternative is a widget
     * that 500s on a division nobody would ever see in the response.
     */
    private static BigDecimal averageHours(long minutes, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(minutes)
                .divide(BigDecimal.valueOf(60L * denominator), 2, RoundingMode.HALF_UP);
    }

    private static String projectParam(Long projectId) {
        return projectId == null ? "" : "&projectId=" + projectId;
    }

    /**
     * The {@code ETag} the contract promises, so a dashboard polling faster than
     * the worker gets {@code 304} and costs nothing.
     *
     * <p>Built from the widget key, the caller's scope and {@code asOf} — the
     * summary tables' own {@code computed_at}. That is the honest validator:
     * the answer is a pure function of those rows, so if the worker has not
     * recomputed, the answer cannot have changed. Hashing the response body
     * would be equivalent and would cost the whole query to discover.
     *
     * <p><b>The scope is in the hash, not only the key.</b> Two callers with
     * different projects ask the same URL and must not share a validator, or an
     * intermediary — or a browser cache after a role change — hands one of them
     * the other's chart.
     *
     * <p>A null {@code asOf} means nothing has been computed for the window;
     * that is a state which can change without any {@code computed_at} to prove
     * it, so it gets no validator at all rather than a stable one that would
     * pin an empty chart in place until the range was changed.
     */
    static String etagOf(String widgetKey, DashboardScope scope, Long projectId,
                         LocalDate from, LocalDate to, Instant asOf) {
        if (asOf == null) {
            return null;
        }
        int hash = java.util.Objects.hash(widgetKey, scope.ownWorkOnly(), scope.userId(),
                scope.projectIds(), projectId, from, to, asOf);
        return Integer.toHexString(hash);
    }
}
