package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObDashboardCard;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObDashboardSummary;
import com.edunext.edutrack.api.security.CallerIdentity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * B-121 · assembles the OB-02 card board — plan §9, screen OB-02.
 *
 * <p>Seven counters, one round trip, "the board's whole first paint". Every
 * number comes from {@link ObDashboardSummaryRepository}; nothing here counts
 * a journey or a step.
 *
 * <h2>The three questions this class answers, in the order they bind</h2>
 *
 * <ol>
 *   <li><b>Can this caller's scope be answered at all?</b>
 *       {@link ObDashboardScope} decides. A narrowed role reaches a board of
 *       seven {@code unavailableReason} cards and the database is not touched,
 *       because there is no query that would have helped.</li>
 *   <li><b>Which days?</b> The two most recent days the table <em>holds</em>,
 *       not today and yesterday — see {@code recentDays}.</li>
 *   <li><b>Is each figure exact?</b> Five of seven always; the three
 *       client-counted cards only when a product is selected. The rest is
 *       {@code countIsUpperBound}, and the reason it exists is the whole first
 *       half of {@link ObDashboardSummaryRepository}'s class note.</li>
 * </ol>
 *
 * <h2>Why the ETag is built here and not in the controller</h2>
 *
 * <p>It is a function of the answer, not of the request: the board is a pure
 * function of the rows the refresh last wrote, so if {@code computed_at} has
 * not moved the answer provably has not either. {@code WidgetService.etagOf}
 * makes the identical argument and adds the one that matters more — <b>the
 * scope is in the hash, not only the URL</b>. Two callers with different
 * onboarding roles ask the same URL and must not share a validator, or an
 * intermediary, or a browser cache after a grant change, hands one of them the
 * other's board.
 */
@Service
class ObDashboardService {

    /** Before B-120's first pass. A-108 makes this reachable on purpose. */
    private static final String NEVER_COMPUTED =
            "No summary has been computed yet, so there is nothing to show. "
                    + "The figures appear after the next refresh.";

    /** The table has days, and none of them has a row for the selected product. */
    private static final String NO_ROW_TODAY =
            "The latest refresh recorded nothing for this product. "
                    + "Choose another product, or check back after the next refresh.";

    /**
     * The scoped table has days, and none of them has a row for this caller.
     *
     * <p>A distinct sentence from {@link #NO_ROW_TODAY} because it is a
     * distinct fact, and from a board of zeroes because a zero would claim
     * nothing of theirs is overdue. The refresh writes no row for an empty
     * scope precisely so this case is reachable; see the migration header.
     */
    private static final String NOTHING_IN_SCOPE =
            "Nothing is in your scope yet — no services are assigned to you, "
                    + "or none on the product you selected. "
                    + "Your board fills as work is assigned to you.";

    private final ObDashboardSummaryRepository summaries;
    private final ObScopeDashboardSummaryRepository scopedSummaries;

    ObDashboardService(ObDashboardSummaryRepository summaries,
                       ObScopeDashboardSummaryRepository scopedSummaries) {
        this.summaries = summaries;
        this.scopedSummaries = scopedSummaries;
    }

    /**
     * A board and the validator that goes with it.
     *
     * <p>Returned as a pair because the {@code ETag} depends on data the
     * response body does not carry — the applied scope — and computing it from
     * the body afterwards would mean hashing a serialisation, which changes
     * when a field is renamed and would expire every client's cache for a
     * change that moved no number.
     *
     * @param etag null when there is nothing stable to validate against: an
     *             unanswerable scope, or a table B-120 has never filled. Both
     *             are states that can change with no {@code computed_at} to
     *             prove it, so pinning them behind a validator would keep an
     *             empty board on screen until the URL changed.
     */
    record Rendered(ObDashboardSummary summary, String etag) {
    }

    /**
     * The board for one caller.
     *
     * @param productId one product's column, or null for every product the
     *                  caller can see — which is a sum over rows, not a stored
     *                  total, "so the two cannot drift" (contract). It is also
     *                  the only case in which three of the cards overstate; see
     *                  {@link ObDashboardSummaryRepository}.
     */
    Rendered summary(CallerIdentity caller, Long productId) {
        ObDashboardScope scope = ObDashboardScope.of(caller);

        // No recognised onboarding role at all. A-111's gate means a real
        // request never gets this far; the branch stays because a record whose
        // behaviour depends on a guard elsewhere having run is one that
        // misbehaves the day something calls it from a scheduled job.
        if (scope.deniesEverything()) {
            return new Rendered(withoutFigures(scope, scope.unavailableReason()), null);
        }

        // Which table can answer this caller. The three unrestricted roles read
        // the org-wide board; OB_SALES and OB_STEP_OWNER read the per-scope one
        // the refresh writes a row of for each of them. Both carry the same
        // columns and the same card arithmetic, so everything below this line
        // is the same for either.
        boolean narrowed = !scope.unrestricted();
        List<LocalDate> days = narrowed
                ? scopedSummaries.recentDays(productId)
                : summaries.recentDays(productId);
        if (days.isEmpty()) {
            return new Rendered(withoutFigures(scope, NEVER_COMPUTED), null);
        }

        Optional<ObDashboardSummaryRepository.Rollup> latest = rollup(scope, narrowed, days.get(0), productId);
        if (latest.isEmpty()) {
            // A day the table lists, with no row on it. For the org-wide board
            // that means this product contributed nothing; for a narrowed
            // caller it means nothing is theirs yet. Neither is the
            // never-computed claim and neither is a board of zeroes — a zero
            // would say this product has no journeys, which is false about a
            // product whose last row was written on Monday.
            return new Rendered(withoutFigures(scope, narrowed ? NOTHING_IN_SCOPE : NO_ROW_TODAY), null);
        }

        // The previous *stored* day, and only when the latest one resolved —
        // otherwise a delta would be measured against a day whose counterpart
        // was never read.
        Optional<ObDashboardSummaryRepository.Rollup> previous = days.size() < 2
                ? Optional.empty()
                : rollup(scope, narrowed, days.get(1), productId);

        ObDashboardSummaryRepository.Rollup today = latest.get();
        List<ObDashboardCard> cards = new ArrayList<>(ObDashboardCardKey.values().length);
        for (ObDashboardCardKey key : ObDashboardCardKey.values()) {
            long count = today.counts().getOrDefault(key, 0L);
            cards.add(ObDashboardCard.of(
                    key,
                    count,
                    previous.map(day -> count - day.counts().getOrDefault(key, 0L)).orElse(null),
                    !ObDashboardSummaryRepository.isExact(productId, key)));
        }

        ObDashboardSummary summary =
                new ObDashboardSummary(List.copyOf(cards), today.computedAt(), scope.appliedScope());
        return new Rendered(summary, etagOf(scope, productId, today.computedAt()));
    }

    /**
     * One day's counters from whichever table answers this caller.
     *
     * <p>The two repositories are deliberately not behind one interface. They
     * differ in the question they can be asked — the scoped one needs a caller
     * and the org-wide one has no place to put it — and an interface hiding
     * that would let a future call site read the org-wide board for a narrowed
     * caller with the compiler's blessing. Here the choice is one boolean, read
     * once, beside the {@code recentDays} call that made the same choice.
     */
    private Optional<ObDashboardSummaryRepository.Rollup> rollup(ObDashboardScope scope,
                                                                 boolean narrowed,
                                                                 LocalDate day,
                                                                 Long productId) {
        return narrowed
                ? scopedSummaries.rollup(day, scope.userId(), productId)
                : summaries.rollup(day, productId);
    }

    /**
     * A board of seven cards carrying a sentence instead of a number.
     *
     * <p>Three situations reach this, and they are deliberately one method
     * because the screen's job is identical in all three: say what is wrong
     * where the number would have been. A zero would do something else — claim
     * that nothing is overdue, which is a factual statement about the data and
     * is false in every one of them.
     *
     * <p>{@code computedAt} is null throughout. Nobody here is being told a
     * figure, so nobody is owed a staleness stamp — and sending one would
     * disclose the refresh cadence to a caller being told nothing else.
     */
    private static ObDashboardSummary withoutFigures(ObDashboardScope scope, String reason) {
        List<ObDashboardCard> cards = new ArrayList<>(ObDashboardCardKey.values().length);
        for (ObDashboardCardKey key : ObDashboardCardKey.values()) {
            cards.add(ObDashboardCard.unavailable(key, reason));
        }
        return new ObDashboardSummary(List.copyOf(cards), null, scope.appliedScope());
    }

    /**
     * The validator the contract promises, "derived from {@code computedAt} and
     * the applied scope".
     *
     * <p>Hashing those rather than the response body is deliberate and is
     * {@code WidgetService.etagOf}'s own argument: the answer is a pure
     * function of the rows the refresh wrote, so hashing the body would be
     * equivalent and would cost the whole query to discover.
     *
     * <p>{@code productId} is in the hash because it changes the answer, and
     * the module role is in it because it changes who may have the answer.
     *
     * <h2>The caller's id is in it too, for a narrowed role only</h2>
     *
     * <p><b>Required since the scoped table landed, and it was not before.</b>
     * Every narrowed caller used to receive a board of sentences and a null
     * validator, so no two of them could collide. Now two Step Owners see
     * genuinely different figures while sharing a role, a product filter and —
     * because one refresh pass stamps every row it writes with one
     * {@code computed_at} — the same instant. Without the id they would hash
     * identically, and any shared cache between them would hand one the
     * other's board: the exact disclosure {@link ObDashboardScope#unrestricted}
     * exists to prevent, arriving through the validator instead of the query.
     *
     * <p>An unrestricted caller contributes a constant rather than their id.
     * Their answer provably does not depend on who is asking — that is what
     * unrestricted means — so an Admin and a Manager go on sharing one
     * validator. The branch reads the same {@code unrestricted} flag that
     * chooses the table, so the two cannot drift apart later: whoever changes
     * which table a role reads changes this with it.
     */
    static String etagOf(ObDashboardScope scope, Long productId, Instant computedAt) {
        if (computedAt == null) {
            return null;
        }
        long caller = scope.unrestricted() ? 0L : scope.userId();
        return Integer.toHexString(Objects.hash(scope.moduleRole(), caller, productId, computedAt));
    }
}
