package com.edunext.edutrack.api.feature.dashboard;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A-056 · the shapes {@code GET /dashboard/widget/{widgetKey}} answers with.
 *
 * <p>Mirrors {@code WidgetResponse} in the contract. One shape carries all six
 * of widgets 7–12 — and widgets 13–20 after them — because a donut, a stacked
 * area, a multi-line and three bars are all "named series of (x, y) points"
 * once the drawing is the client's problem. Six bespoke response types would
 * have been six chances for the drill-down convention to be implemented
 * differently.
 */
final class WidgetDtos {

    private WidgetDtos() {
    }

    record WidgetResponse(Widget data) {
    }

    /**
     * @param asOf              when the summary tables were last recomputed.
     *                          Same reasoning as the cards: these rows are up
     *                          to five minutes old by design (A-051), and a
     *                          dashboard that hides its own staleness invites
     *                          somebody to trust a number that moved four
     *                          minutes ago. It is also what the {@code ETag} is
     *                          built from — see {@link WidgetService#etagOf}.
     * @param unavailableReason non-null when the caller's role has no table
     *                          that can answer this widget, with the reason in
     *                          plain words. <b>Not an empty series and not a
     *                          404.</b> An empty series renders as "no tickets
     *                          matched", which is a factual claim about the
     *                          data and is false; a 404 on a route the role
     *                          legitimately holds reads to the client as a bug
     *                          and would have it retry. Saying so explicitly is
     *                          also what stops the frontend re-deriving the
     *                          role rule for itself, which is the drift
     *                          {@link DashboardScope} exists to prevent.
     */
    record Widget(String key, Instant asOf, List<Series> series, String unavailableReason) {

        static Widget unavailable(String key, String reason) {
            return new Widget(key, null, List.of(), reason);
        }

        static Widget of(String key, Instant asOf, List<Series> series) {
            return new Widget(key, asOf, series, null);
        }
    }

    /**
     * One plotted series. A donut and a bar chart have exactly one; the stacked
     * area has three; the velocity chart has one per resource.
     */
    record Series(String name, List<Point> points) {
    }

    /**
     * @param x        the category or date this point sits at. A string in
     *                 every case — an ISO date for the time series, a task type
     *                 or level name for the categorical ones. The contract
     *                 permits a number too; nothing needs one, and a numeric
     *                 axis label would have to be formatted by the client,
     *                 which is where "3" and "March" start disagreeing.
     * @param drillDown the pre-filtered list this segment opens, or null where
     *                 no list expresses it. §S-05's rule is that <b>every chart
     *                 segment</b> deep-links, not only every card, and it is
     *                 built server-side for A-055's reason: the filter that
     *                 produced the number and the filter the list applies must
     *                 be the same string, or the chart and the list it opens
     *                 disagree and the user believes the list.
     */
    record Point(String x, BigDecimal y, String drillDown) {

        static Point of(String x, long y, String drillDown) {
            return new Point(x, BigDecimal.valueOf(y), drillDown);
        }
    }
}
