package com.edunext.edutrack.api.feature.onboarding.reports;

import java.time.Instant;

/**
 * B-122 · a step's health colour, computed in one place.
 *
 * <p>{@code ObRag} in the contract: "computed identically at step, journey and
 * client level: worst-wins upward (plan §5.9). AMBER at a configurable share of
 * TAT — default 75% — so the warning arrives before the breach rather than
 * reporting it." This is the step-level half of that, which is the only half
 * OB-10 needs: {@code stuck-and-aging} colours steps.
 *
 * <h2>Why this is derived rather than read</h2>
 *
 * <p>Nothing stores it. {@code ob_dashboard_summary} has {@code rag_green} /
 * {@code rag_amber} / {@code rag_red} <em>counts</em>, written by B-120, and a
 * count cannot be resolved back to which step was which colour. There is no
 * {@code rag} column on {@code ob_journey_steps} and A-107 did not add one, on
 * the reasoning every derived-state column invites: it would need rewriting on
 * a timer as steps age past thresholds nothing touched.
 *
 * <p>So it is a function of {@code started_at}, {@code due_at} and now, and it
 * lives in its own class rather than inside the runner because
 * <b>{@code ?rag=} filters on the value this produces</b>. A formula in SQL for
 * the chip and a formula in Java for the filter would be two formulas, and the
 * screen would eventually show an AMBER chip on a row that a RED filter had
 * returned.
 *
 * <h2>The threshold is a constant here and the contract calls it configurable</h2>
 *
 * <p>Named rather than quietly hardcoded: there is no setting behind it yet, so
 * 75% is the contract's stated default and nothing reads it from anywhere. When
 * the setting lands, this constant is the one call site to change — which is
 * the reason for the class as much as the shared formula is.
 */
final class ObStepRag {

    private ObStepRag() {
    }

    /** {@code ObRag}'s stated default. There is no setting behind it yet. */
    static final double AMBER_SHARE_OF_TAT = 0.75;

    static final String GREEN = "GREEN";
    static final String AMBER = "AMBER";
    static final String RED = "RED";

    /**
     * The colour of one step, or null where there is nothing to colour.
     *
     * <p>Null is a real answer and not a failure: {@code ObRag}'s own wording is
     * "null where there is nothing to colour". A step with no {@code due_at}
     * has no TAT window to be measured against — which today is every step on a
     * deployment where C-105's clock has not run — and calling that GREEN would
     * be a health claim made from no evidence. It renders as an em dash and it
     * matches no {@code ?rag=} value, so a filtered view never silently
     * includes unmeasurable rows.
     *
     * @param startedAt when the TAT window opened, or null for a step not yet
     *                  started. A step with a due date and no start is treated
     *                  as having its whole window ahead of it, so it can be RED
     *                  (the date has passed) but never AMBER (there is no
     *                  elapsed share to take 75% of).
     * @param dueAt     the pinned TAT deadline, already calendar-aware —
     *                  C-105 computes it through {@code WorkingHoursService}.
     * @param now       the instant the report ran.
     */
    static String of(Instant startedAt, Instant dueAt, Instant now) {
        if (dueAt == null) {
            return null;
        }
        if (!now.isBefore(dueAt)) {
            return RED;
        }
        if (startedAt == null || !startedAt.isBefore(dueAt)) {
            // No window to take a share of. Not yet breached, so not RED, and
            // nothing supports AMBER — the honest answer is the good one.
            return GREEN;
        }
        long window = dueAt.toEpochMilli() - startedAt.toEpochMilli();
        long elapsed = now.toEpochMilli() - startedAt.toEpochMilli();
        return elapsed >= window * AMBER_SHARE_OF_TAT ? AMBER : GREEN;
    }

    /**
     * Whether a row matches a {@code ?rag=} value.
     *
     * <p>An unrecognised filter value narrows to nothing rather than throwing,
     * which is {@link ObReportFilters}' stated rule for this parameter: a
     * filter every other one of which narrows to nothing is not the place to
     * start rejecting requests. A null filter matches everything, including
     * uncoloured rows.
     */
    static boolean matches(String rag, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        return filter.equalsIgnoreCase(rag);
    }
}
