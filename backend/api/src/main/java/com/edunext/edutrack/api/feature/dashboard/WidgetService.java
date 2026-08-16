package com.edunext.edutrack.api.feature.dashboard;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * split, no aging buckets, and no created/reopened counts — the last because a
 * ticket is raised by a reporter and reopened by a manager, and neither is the
 * assignee whose dashboard this is.
 *
 * <p>Those widgets return {@code unavailableReason} rather than an empty series
 * or a 404, and the reason is in {@link WidgetDtos.Widget}. Widgets 9 and 10 do
 * work for a delivery role, scoped to themselves — which is exactly the subset
 * §S-05 says the developer dashboard shows, so A-062 inherits this rather than
 * re-deciding it.
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

    /** The six A-056 owns. The contract's other eight keys belong to A-057 to A-059. */
    private static final List<String> IMPLEMENTED = List.of(
            "type-donut", "daily-stacked", "velocity", "resource-load", "priority-bar", "aging-buckets");

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

        LocalDate end = to != null ? to : LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_WINDOW_DAYS - 1L);

        DashboardScope scope = DashboardScope.of(caller);
        Instant asOf = summaries.computedAt(start, end).orElse(null);

        WidgetDtos.Widget widget = switch (widgetKey) {
            case "type-donut" -> typeDonut(scope, projectId, start, end, asOf);
            case "daily-stacked" -> dailyStacked(scope, projectId, start, end, asOf);
            case "velocity" -> velocity(scope, start, end, asOf);
            case "resource-load" -> resourceLoad(scope, start, end, asOf);
            case "priority-bar" -> priorityBar(scope, projectId, start, end, asOf);
            case "aging-buckets" -> agingBuckets(scope, projectId, start, end, asOf);
            default -> throw new IllegalStateException("implemented key with no branch: " + widgetKey);
        };

        // An unavailable widget gets no validator. Its answer does not depend on
        // computed_at at all — it depends on the caller's role — so an ETag
        // built from the summary tables would let a 304 outlive a role change
        // and keep showing somebody the refusal after they had been promoted.
        String etag = widget.unavailableReason() != null
                ? null
                : etagOf(widgetKey, scope, projectId, start, end, asOf);

        return Optional.of(new Rendered(widget, etag));
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
            String window = "from=" + day.day() + "&to=" + day.day() + projectParam(projectId);
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
            // §S-05 lists aging in the developer variant, and A-062 will need it.
            // resource_daily_stats has no aging columns, so that is a schema
            // change on A-050's table and belongs to the task that needs it
            // rather than being smuggled in here.
            return WidgetDtos.Widget.unavailable("aging-buckets", NO_RESOURCE_EQUIVALENT);
        }

        WidgetRepository.StockBreakdown stock =
                widgets.stockBreakdown(from, to, scope.projectIds(), projectId).orElse(EMPTY_BREAKDOWN);

        // The labels are the schema's edges, not §S-05's. The blueprint draws
        // 0–2 / 3–5 / 6–10 / >10; A-050 stored 0–2 / 3–7 / 8–30 / 31+ and fixed
        // them in the column deliberately, so that a bucket boundary cannot move
        // between two loads of the same day. Labelling them the blueprint's way
        // over the schema's numbers would be the one genuinely dishonest option.
        //
        // drillDown is null throughout: the ticket list has no age filter, and
        // §S-05's own drill-down column asks for "age range". Inventing a
        // parameter the list ignores would give a segment that opens a list
        // contradicting it, which is worse than a segment that does not open.
        List<WidgetDtos.Point> points = List.of(
                new WidgetDtos.Point("0–2 days", java.math.BigDecimal.valueOf(stock.aging02()), null),
                new WidgetDtos.Point("3–7 days", java.math.BigDecimal.valueOf(stock.aging37()), null),
                new WidgetDtos.Point("8–30 days", java.math.BigDecimal.valueOf(stock.aging830()), null),
                new WidgetDtos.Point("31+ days", java.math.BigDecimal.valueOf(stock.aging31Plus()), null));

        return WidgetDtos.Widget.of("aging-buckets", asOf,
                List.of(new WidgetDtos.Series("Open by age", points)));
    }

    private static final WidgetRepository.StockBreakdown EMPTY_BREAKDOWN =
            new WidgetRepository.StockBreakdown(0, 0, 0, 0, 0, 0, 0, 0);

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
