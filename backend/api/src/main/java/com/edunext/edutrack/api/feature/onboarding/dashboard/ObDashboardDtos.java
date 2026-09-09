package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.edunext.edutrack.common.pagination.PageMeta;

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

    // ── B-127 · the slide-over behind one card ──────────────────────────────

    /** Mirrors {@code ObDashboardItemType} in the contract. */
    enum ObDashboardItemType {
        SERVICE, PREREQUISITE
    }

    /**
     * {@code ObProductRef}, restated locally rather than imported — the same
     * three-field mirror every onboarding package keeps of its own, per
     * {@code ObClientDtos.ObProductRef}'s own precedent, so that a change to
     * one package's response shape is never a silent change to another's.
     */
    record ObProductRef(long id, String code, String name) {
    }

    /** {@code UserRef}, restated locally — see {@link ObProductRef}'s own note. */
    record UserRef(long id, String displayName) {
    }

    /**
     * One row of the slide-over — mirrors {@code ObDashboardItem}.
     *
     * @param itemId    an {@code ob_journey_steps} id when {@code itemType} is
     *                  {@code SERVICE}, an {@code ob_client_prereq_tasks} id
     *                  when it is {@code PREREQUISITE}. Two id spaces behind
     *                  one field, exactly as the contract states.
     * @param journeyId null on a prerequisite.
     * @param product   null on a prerequisite.
     * @param owner     null on a prerequisite, whose counterparty is the
     *                  client rather than an implementor.
     * @param status    an {@code ObJourneyStepStatus} or an
     *                  {@code ObPrereqTaskStatus} depending on
     *                  {@code itemType} — a plain string, per the contract's
     *                  own reasoning: a display column gains nothing from a
     *                  generated client forced to discriminate two enums to
     *                  render a chip.
     */
    record ObDashboardItem(ObDashboardItemType itemType, long itemId, long obClientId, String obClientName,
                           Long journeyId, ObProductRef product, String title, UserRef owner, String status,
                           Instant dueAt, boolean isOverdue) {

        static ObDashboardItem of(ObDashboardCardItemsRepository.ItemRow row) {
            ObProductRef product = row.productId() == null ? null
                    : new ObProductRef(row.productId(), row.productCode(), row.productName());
            UserRef owner = row.ownerUserId() == null ? null
                    : new UserRef(row.ownerUserId(), row.ownerName());
            return new ObDashboardItem(
                    ObDashboardItemType.valueOf(row.itemType()), row.itemId(), row.obClientId(), row.obClientName(),
                    row.journeyId(), product, row.title(), owner, row.status(), row.dueAt(), row.isOverdue());
        }
    }

    /**
     * {@code meta} for {@code ObDashboardItemListResponse} — {@code Meta}
     * (A-053's {@code nextCursor}/{@code hasMore}) plus {@code computedAt},
     * repeating the card's own so a screen can say which number these rows
     * belong to.
     *
     * <p>Not {@link com.edunext.edutrack.common.pagination.PageMeta} alone —
     * that type deliberately carries no third field, and the contract's own
     * {@code allOf} extension is what B-127 added to hold this one; see the
     * commit that fixed it.
     */
    record ObDashboardItemListMeta(String nextCursor, boolean hasMore, Instant computedAt) {

        static ObDashboardItemListMeta of(PageMeta page, Instant computedAt) {
            return new ObDashboardItemListMeta(page.nextCursor(), page.hasMore(), computedAt);
        }
    }

    record ObDashboardItemListResponse(List<ObDashboardItem> data, ObDashboardItemListMeta meta) {
    }
}
