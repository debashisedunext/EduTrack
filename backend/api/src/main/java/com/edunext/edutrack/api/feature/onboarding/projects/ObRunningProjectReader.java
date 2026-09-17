package com.edunext.edutrack.api.feature.onboarding.projects;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import com.edunext.edutrack.api.feature.onboarding.projects.ObProjectDtos.ObProjectSummary;
import com.edunext.edutrack.common.pagination.PageLimit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The one way another feature reads this one's running projects.
 *
 * <h2>What it is for</h2>
 *
 * <p>OB-02's project board counts, buckets and lists every running project by
 * its completion date. That date, and the delay against it, are
 * {@link ObProjectService}'s working-calendar arithmetic — the same arithmetic
 * the Projects grid prints. Rather than let the dashboard grow a second copy
 * of it (which would be right until the day somebody fixed a rounding rule in
 * one of them), the dashboard calls this and decorates what it gets.
 *
 * <p>So this class is a seam, deliberately narrow: one method, a projection
 * record, no write path, and no way to reach {@code ObProjectDtos} or the
 * repository behind it. {@code ObClientScope} travels in from the caller
 * because scope is the caller's, never this class's to assume — the same rule
 * every other read in the module follows.
 *
 * <h2>Why it pages rather than taking a limit</h2>
 *
 * <p>A board that showed the first fifty projects and counted only those would
 * be a board that lies, and {@code PageLimit.MAX} is 200. So this walks the
 * cursor until the underlying read is exhausted or {@link #CEILING} rows have
 * been collected, and {@link #hitCeiling(List)} lets the caller say so on the
 * wire rather than silently reporting a partial count as a total.
 *
 * <p>The ceiling is generous on purpose: a deployment with more than two
 * thousand <em>simultaneously running</em> onboarding projects has outgrown a
 * single-screen board, and the honest answer there is a filtered view rather
 * than a longer loop.
 */
@Service
public class ObRunningProjectReader {

    /** As many running projects as one board may summarise before it has to admit it is truncated. */
    public static final int CEILING = 2000;

    /** {@code PageLimit.MAX} — the largest page the underlying read will serve, so the fewest round trips. */
    private static final int PAGE = PageLimit.MAX;

    private final ObProjectService projects;

    ObRunningProjectReader(ObProjectService projects) {
        this.projects = projects;
    }

    /**
     * Every {@code RUNNING} project the caller's scope admits, newest first.
     *
     * <p>Empty rather than an exception for a caller whose module role can see
     * nothing — {@code ObProjectService.list} already answers that way, and a
     * board with no rows and every count at zero is the truthful rendering of
     * "you have no projects", where a 403 would read as a bug.
     */
    public List<ObRunningProject> runningProjects(ObClientScope scope) {
        List<ObRunningProject> collected = new ArrayList<>();
        String cursor = null;
        do {
            var page = projects.list(scope, null, null, null, "RUNNING", null, null, cursor, PAGE);
            page.data().forEach(row -> collected.add(project(row)));
            cursor = page.meta().nextCursor();
            /*
              `hasMore` is not the loop condition: `nextCursor` is null exactly
              when there is no next page, and a cursor that is present while
              `hasMore` is false would spin. Reading the one field that decides
              it keeps this loop terminating for the same reason the page's own
              trim does.
            */
        } while (cursor != null && collected.size() < CEILING);
        return collected;
    }

    /** True when the list came back at the ceiling and may therefore be short of the truth. */
    public static boolean hitCeiling(List<ObRunningProject> projects) {
        return projects.size() >= CEILING;
    }

    private static ObRunningProject project(ObProjectSummary row) {
        return new ObRunningProject(
                row.id(), row.name(), row.startDate(),
                row.client().id(), row.client().name(), row.client().clientCode(), row.client().city(),
                row.product().id(), row.product().code(), row.product().name(),
                row.salesPerson() == null ? null : row.salesPerson().id(),
                row.salesPerson() == null ? null : row.salesPerson().displayName(),
                row.implementor() == null ? null : row.implementor().id(),
                row.implementor() == null ? null : row.implementor().displayName(),
                row.gateStatus(), row.currentStage(),
                row.delayedByDays(), row.tentativeCompletion(), row.totalTatDays());
    }
}
