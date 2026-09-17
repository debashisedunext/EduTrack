package com.edunext.edutrack.api.feature.onboarding.dashboard;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * C-141 · what the caller's two review cards say.
 *
 * <h2>One route, two cards, because it is one question</h2>
 *
 * <p>A manager asks "how much is waiting on me"; an implementor asks "what
 * became of what I sent". Both are answered out of the same row of
 * {@code ob_implementor_daily_stats}, and plenty of people are both — a
 * manager who still carries tasks of their own. Splitting this into two routes
 * would make that person's dashboard two requests and would force the client
 * to decide which of them to make, which is a decision it does not have the
 * information to take.
 *
 * <p>So the figures come back together and the <em>screen</em> decides what to
 * draw: a card with a zero is not drawn, and somebody who is only ever an
 * implementor simply never has a non-zero {@code reviewsPending}.
 *
 * <h2>An empty table is not an error</h2>
 *
 * <p>On a database the stats worker has never run against there is no
 * {@code stat_date} at all. That answers four zeroes with a null
 * {@code computedAt} rather than a 404 or an exception — the same shape the
 * rest of the board uses for "no summary has been computed yet", and the
 * client can say so rather than showing a figure it made up.
 */
@Service
public class ObReviewSummaryService {

    private final ObReviewSummaryRepository reads;

    ObReviewSummaryService(ObReviewSummaryRepository reads) {
        this.reads = reads;
    }

    @Transactional(readOnly = true)
    public ObDashboardDtos.ObReviewSummary summaryFor(long userId) {
        Optional<LocalDate> day = reads.latestStatDate();
        if (day.isEmpty()) {
            return new ObDashboardDtos.ObReviewSummary(0, 0, 0, 0, null);
        }
        return reads.forUser(day.get(), userId)
                .map(r -> new ObDashboardDtos.ObReviewSummary(
                        r.reviewsPending(), r.sentForReview(),
                        r.reviewsApproved(), r.reviewsRejected(), r.computedAt()))
                .orElseGet(() -> new ObDashboardDtos.ObReviewSummary(0, 0, 0, 0, null));
    }
}
