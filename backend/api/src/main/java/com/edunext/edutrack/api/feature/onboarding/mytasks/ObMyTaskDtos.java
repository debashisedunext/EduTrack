package com.edunext.edutrack.api.feature.onboarding.mytasks;

import com.edunext.edutrack.common.pagination.PageMeta;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * {@code /onboarding/my-tasks} — the implementor's own queue.
 *
 * <h2>One row, seven columns, no second request</h2>
 *
 * <p>Every column here already exists somewhere: the task on
 * {@code ob_journey_steps}, the service on {@code ob_journeys}, the step on the
 * template's stage group, the project and client on their own tables. None of
 * them is on one response today — the journey read is per journey, so building
 * this grid from what exists would be one request per project the implementor
 * touches, and the whole point of the screen is that it spans projects.
 *
 * <h2>There is no {@code ownerUserId} parameter, and that is the design</h2>
 *
 * <p>The caller is the filter. A parameter a browser could change would let one
 * implementor read a colleague's queue by guessing a user id — the same reason
 * the reports funnel ignores {@code ownerUserId} for a Step Owner rather than
 * answering it.
 */
interface ObMyTaskDtos {

    /**
     * One row of My Tasks.
     *
     * @param step       the implementation stage the task sits in — <b>called a
     *                   Step everywhere a person reads it</b>. The API keeps the
     *                   stage vocabulary because {@code ob_journey_steps} is
     *                   already the task table; see the glossary in
     *                   {@code ObProjectTree.tsx}
     * @param isOverdue  {@code dueAt} is in the past. A plain comparison, not a
     *                   working-calendar figure: the calendar decides <em>how
     *                   many days</em> late something is, and OB-02 prints that.
     *                   Whether a deadline has passed is the same question in
     *                   every calendar
     * @param dueAt      null for a task that has never activated, which is why
     *                   those sort last rather than first
     */
    @Schema(description = "One open task belonging to the calling implementor.")
    record ObMyTask(
            long taskId,
            String taskName,
            String status,
            Instant dueAt,
            boolean isOverdue,
            long projectId,
            String projectName,
            long obClientId,
            String obClientName,
            String obClientCode,
            long journeyId,
            String serviceName,
            long stepKey,
            String stepName,
            int stepSequence,
            /**
             * Rows of this task sitting on the reviewer's desk.
             *
             * <p>Reads two ways depending on who has the page open, and that is
             * deliberate rather than sloppy: to a manager it is a queue ("3 rows
             * to read"), to the implementor it is a wait ("3 rows out"). One
             * number, because it is one fact.
             */
            int rowsOut,
            /** Returned rows this task's owner has not opened yet. */
            int rowsReturned,
            /** Approved rows this task's owner has not opened yet. */
            int rowsApproved,
            /**
             * Whether the caller must verify this task rather than merely own
             * it: {@code status} is {@code PENDING_REVIEW} and the caller is
             * the project's named manager, or holds {@code OB_ADMIN}.
             *
             * <p>The other three ways a task reaches this page put it there
             * because the caller owns it; this is the fourth, and the only one
             * that means a review rather than a wait. My Tasks' "Pending for
             * verification" tab is this field, and nothing else — see
             * {@code ObMyTaskReadRepository.MINE}'s own javadoc for why the
             * same {@code PENDING_REVIEW} status reads oppositely for the two
             * people it can belong to.
             */
            boolean pendingMyVerification) {
    }

    /**
     * {@code meta.isReviewerForAnyProject} — not {@link PageMeta} alone,
     * which deliberately carries no third field (see its own javadoc).
     *
     * <p>Page-independent, unlike {@code ObMyTask.pendingMyVerification}: a
     * caller who reviews at least one project must see the "Pending for
     * verification" tab even on a page whose ten rows happen to be their own
     * work rather than anybody's review — see
     * {@code ObMyTaskReadRepository.reviewsAnyProject}.
     */
    record ObMyTaskListMeta(String nextCursor, boolean hasMore, boolean isReviewerForAnyProject) {

        static ObMyTaskListMeta of(PageMeta page, boolean isReviewerForAnyProject) {
            return new ObMyTaskListMeta(page.nextCursor(), page.hasMore(), isReviewerForAnyProject);
        }
    }

    record ObMyTaskListResponse(List<ObMyTask> data, ObMyTaskListMeta meta) {
    }

    record ObMyTaskResponse(ObMyTask data) {
    }
}
