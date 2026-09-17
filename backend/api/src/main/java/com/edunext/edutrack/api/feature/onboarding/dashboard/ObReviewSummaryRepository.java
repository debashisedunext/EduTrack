package com.edunext.edutrack.api.feature.onboarding.dashboard;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * C-141 · the caller's own review counters, read from
 * {@code ob_implementor_daily_stats}.
 *
 * <h2>Why this is not a {@code COUNT(*)} over the check list</h2>
 *
 * <p>It would be a short query and it is forbidden: CLAUDE.md's "never live
 * {@code COUNT(*)} for dashboards" exists because a card is read by everybody
 * on every page load, and the obvious aggregate here walks every check-list
 * row of every open task of every project a manager owns. The four columns
 * this reads are written by {@code ObStatsRefreshWorker} on the same schedule
 * as the rest of the board.
 *
 * <p><b>The price, stated plainly:</b> a card is as fresh as the last worker
 * run — {@code PT5M} committed — so "3 rows pending your verification" can be
 * minutes behind the queue it describes. That is tolerable for a figure whose
 * job is to make somebody look, and it is exactly why the My Tasks highlight
 * next to it is computed live instead: the count invites, the highlight
 * directs, and only the second one would send somebody to an empty task if it
 * were stale.
 *
 * <h2>One row, keyed by the caller</h2>
 *
 * <p>{@code (stat_date, user_id)} is already the table's primary key, which is
 * the exact grain both cards need, so there is no new table and no
 * {@code make grants} run. A caller with no row — somebody who has never held
 * onboarding work — reads as four zeroes rather than as an error, because
 * "nothing is waiting on you" is the true answer and an empty card is not a
 * failure.
 */
@Repository
class ObReviewSummaryRepository {

    private static final String SQL = """
            SELECT s.reviews_pending  AS reviewsPending,
                   s.sent_for_review  AS sentForReview,
                   s.reviews_approved AS reviewsApproved,
                   s.reviews_rejected AS reviewsRejected,
                   s.computed_at      AS computedAt
              FROM ob_implementor_daily_stats s
             WHERE s.stat_date = :statDate
               AND s.user_id   = :userId
            """;

    private final JdbcClient jdbc;

    ObReviewSummaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The latest day the worker has written, or empty on a database it has never run against. */
    Optional<LocalDate> latestStatDate() {
        return jdbc.sql("SELECT MAX(stat_date) FROM ob_implementor_daily_stats")
                .query(LocalDate.class)
                .optional();
    }

    Optional<Row> forUser(LocalDate statDate, long userId) {
        return jdbc.sql(SQL)
                .param("statDate", statDate)
                .param("userId", userId)
                .query((rs, n) -> new Row(
                        rs.getInt("reviewsPending"),
                        rs.getInt("sentForReview"),
                        rs.getInt("reviewsApproved"),
                        rs.getInt("reviewsRejected"),
                        rs.getTimestamp("computedAt") == null
                                ? null : rs.getTimestamp("computedAt").toInstant()))
                .optional();
    }

    /**
     * @param reviewsPending  rows waiting on this user <em>as a manager</em>
     * @param sentForReview   rows this user has out with their own manager
     * @param reviewsApproved rows of theirs approved on this stat day
     * @param reviewsRejected rows of theirs sent back on this stat day
     */
    record Row(int reviewsPending, int sentForReview, int reviewsApproved, int reviewsRejected,
               Instant computedAt) {
    }
}
