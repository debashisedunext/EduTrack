package com.edunext.edutrack.api.feature.onboarding.mytasks;

import com.edunext.edutrack.api.feature.onboarding.mytasks.ObMyTaskDtos.ObMyTask;
import com.edunext.edutrack.api.feature.onboarding.mytasks.ObMyTaskDtos.ObMyTaskListResponse;
import com.edunext.edutrack.api.feature.onboarding.mytasks.ObMyTaskReadRepository.Row;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageLimit;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Assembles My Tasks.
 *
 * <p>Thin on purpose: the query does the scoping and the ordering, because both
 * are things a caller must not be able to influence. What is left here is the
 * page boundary and one derived field.
 */
@Service
class ObMyTaskService {

    private final ObMyTaskReadRepository reads;
    private final Clock clock;

    ObMyTaskService(ObMyTaskReadRepository reads, Clock clock) {
        this.reads = reads;
        this.clock = clock;
    }

    /**
     * @param admin the caller holds {@code OB_ADMIN}, which adds every task
     *              awaiting review to their page rather than only those on
     *              projects they manage — the unsticking path for a project
     *              whose named manager has left. Everybody else, including an
     *              {@code OB_MANAGER}, gets only the reviews their own
     *              projects have earned them; see
     *              {@link ObMyTaskReadRepository}'s own javadoc.
     */
    ObMyTaskListResponse list(long userId, boolean admin, String cursor, Integer limitParam) {
        int limit = PageLimit.clamp(limitParam);
        Instant now = clock.instant();

        List<Row> fetched = reads.openTasksOf(userId, admin, Cursor.decode(cursor),
                PageLimit.fetchSize(limit));

        CursorPage<Row> page = CursorPage.of(fetched, limit,
                row -> new Cursor(row.sortKey(), row.taskId()));

        return new ObMyTaskListResponse(
                page.data().stream().map(row -> task(row, now)).toList(),
                page.meta());
    }

    /**
     * One task of the caller's, for the focused page.
     *
     * <p>Empty for a task that is not theirs, which the controller answers as a
     * 404. Not a 403: that would confirm the task exists, and task ids are
     * sequential — CONVENTIONS.md §7, applied here as everywhere else.
     */
    Optional<ObMyTask> findOwnTask(long userId, boolean admin, long taskId) {
        Instant now = clock.instant();
        return reads.findOwnTask(userId, admin, taskId).map(row -> task(row, now));
    }

    /**
     * {@code isOverdue} is decided here rather than in SQL so it is decided
     * against the service's clock — the one a test can move — and not against
     * the database's.
     *
     * <p>It is a plain comparison, deliberately. The working calendar decides
     * <em>how many days</em> late something is, which is what OB-02 prints and
     * why {@code delayedByDays} routes through {@code WorkingHoursService};
     * whether a deadline has already passed is the same answer in every
     * calendar, and running it through one would only invite the two figures to
     * disagree at a weekend boundary.
     */
    private static ObMyTask task(Row row, Instant now) {
        boolean overdue = row.dueAt() != null && row.dueAt().isBefore(now);
        return new ObMyTask(
                row.taskId(),
                row.taskName(),
                row.status(),
                row.dueAt(),
                overdue,
                row.projectId(),
                row.projectName(),
                row.obClientId(),
                row.obClientName(),
                row.obClientCode(),
                row.journeyId(),
                row.serviceName(),
                row.stepKey(),
                row.stepName(),
                row.stepSequence(),
                row.rowsOut(),
                row.rowsReturned(),
                row.rowsApproved());
    }
}
