package com.edunext.edutrack.api.feature.onboarding.dashboard;

import java.time.Instant;
import java.util.List;

/**
 * B-121 · the shapes {@code GET /onboarding/dashboard/summary} answers with.
 *
 * <p>Mirrors {@code ObDashboardSummaryResponse} in {@code contracts/openapi.yaml}.
 * Records rather than a builder, and a package-private holder rather than seven
 * top-level files, matching {@code WidgetDtos} one module over.
 *
 * <p>These are deliberately <em>not</em> the ticketing dashboard's types.
 * A-115's ArchUnit rule refuses the import, and A-118 records the three reasons
 * at length above {@code /onboarding/dashboard/summary}: the shapes differ, the
 * vocabularies are not subsets of each other, and the module gate sits on the
 * route tree so a shared route would disclose that onboarding is deployed.
 */
final class ObDashboardDtos {

    private ObDashboardDtos() {
    }

    record ObDashboardSummaryResponse(ObDashboardSummary data) {
    }

    /**
     * @param cards       all seven, always, in {@link ObDashboardCardKey} order.
     *                    A card whose count is zero is drawn as zero rather
     *                    than omitted — an absent card and a card reading
     *                    nought are different claims, and only one of them is
     *                    true when nothing is overdue.
     * @param computedAt  when {@code ob_dashboard_summary} was last refreshed,
     *                    or <b>null when B-120 has never run</b>. A-108 makes
     *                    that a reachable state on purpose ("both start empty
     *                    and fill forward from the day they land"), and it is a
     *                    different claim from a computed board that happens to
     *                    be quiet.
     * @param appliedScope what A-112's rule narrowed the counts to, in a
     *                    sentence. Sent even when nothing was narrowed: it is
     *                    what lets a Step Owner comparing their board against a
     *                    colleague's see why the numbers differ without asking.
     */
    record ObDashboardSummary(List<ObDashboardCard> cards, Instant computedAt, String appliedScope) {
    }

    /**
     * @param key                 the kebab-case token, serialised by
     *                            {@link ObDashboardCardKey#wireName()}.
     * @param count               the figure. 0 and meaningless whenever
     *                            {@code unavailableReason} is set.
     * @param deltaFromYesterday  change against the previous <em>stored</em>
     *                            day, or null when there is no earlier day to
     *                            compare against. Null rather than 0, because 0
     *                            is a claim that nothing moved.
     * @param countIsUpperBound   true when {@code count} may overstate because
     *                            it was summed across the summary table's
     *                            product rows and this card counts clients.
     *                            Always false with a {@code productId}. See
     *                            {@link ObDashboardSummaryRepository} for why
     *                            the exact figure is not recoverable.
     * @param unavailableReason   why this card carries no number, or null when
     *                            it carries one. See {@link ObDashboardScope};
     *                            it is A-056's {@code Widget.unavailableReason}
     *                            transferred whole, including its reason for
     *                            being on the wire rather than re-derived by
     *                            the SPA.
     */
    record ObDashboardCard(ObDashboardCardKey key,
                           long count,
                           Long deltaFromYesterday,
                           boolean countIsUpperBound,
                           String unavailableReason) {

        static ObDashboardCard of(ObDashboardCardKey key, long count, Long delta, boolean upperBound) {
            return new ObDashboardCard(key, count, delta, upperBound, null);
        }

        /**
         * A card with no number at all.
         *
         * <p>{@code countIsUpperBound} is false rather than true: there is no
         * count to bound, and claiming one would have a client render "at most
         * 0" beside a sentence saying the figure is unavailable.
         */
        static ObDashboardCard unavailable(ObDashboardCardKey key, String reason) {
            return new ObDashboardCard(key, 0L, null, false, reason);
        }
    }
}
