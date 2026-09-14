package com.edunext.edutrack.api.feature.onboarding.journeys;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * How long a Module Service <b>takes</b> — the critical path through its
 * tasks, in working days.
 *
 * <h2>Why this replaced {@code sum(tat_days)}</h2>
 *
 * <p>Every total TAT in the module used to be a plain Σ over the task rows,
 * which answers "how much work is in this service" and was being read —
 * everywhere it is printed — as "how long does this service take". Those are
 * different numbers the moment anything runs alongside anything else, and a
 * Module Service is built to have parallel work: that is what a null
 * {@code depends_on_step_id} <em>means</em>.
 *
 * <p>The rule in one line: <b>a dependency adds, a parallel branch does
 * not.</b> Two tasks of 1 and 2 days that wait for nothing both start on day
 * 1, so the service turns round in 2 days. Chain the second behind the first
 * and it is 3, because now the days really are consecutive. With several
 * chains the longest one is the answer and shortening any other saves
 * nothing.
 *
 * <h2>It is the designer's own arithmetic, server-side</h2>
 *
 * <p>{@code journeyTemplateTree.ts} already walks exactly this to draw the
 * Schedule column: a root on day 1, a dependent the day after its predecessor
 * ends, and {@code spanDays} as the last day anything ends on. The catalogue
 * card is the same question asked without opening the service, so it has to
 * be the same walk — a second arithmetic here is a number that disagrees with
 * the bars on the next screen.
 *
 * <h2>Working days, and no calendar</h2>
 *
 * <p>{@code tatDays} is working days (the v1.2 unit change) and so is the
 * result. Weekends, holidays and leave are applied exactly once, on a
 * client's journey, through the working calendar — a template has no client
 * and therefore no dates at all.
 */
public final class JourneyTatCalculator {

    private JourneyTatCalculator() {
    }

    /**
     * The little a task needs to be placed. A record rather than the entity so
     * the product catalogue, which reads a projection across many services in
     * one query, can use the same walk as the service that has entities in
     * hand.
     *
     * @param dependsOnStepId the task this one waits for, or null to start on
     *                        day 1 — <b>parallel, not "first"</b>
     */
    public record Task(long id, int tatDays, Long dependsOnStepId) {
    }

    /**
     * The last working day any of these tasks ends on, counting day 1 as the
     * day the journey starts. {@code 0} for no tasks at all, which is the
     * usual state of a service somebody has just created.
     *
     * <p>A {@code dependsOnStepId} naming something outside this collection
     * starts on day 1 rather than being dropped — the same choice
     * {@code buildStepTree} makes, and for the same reason: a task vanishing
     * from a total is the one outcome that must not be possible, whatever the
     * data does.
     */
    public static int criticalPathDays(Collection<Task> tasks) {
        Map<Long, Task> byId = new LinkedHashMap<>();
        for (Task task : tasks) {
            byId.put(task.id(), task);
        }
        Map<Long, Integer> endDays = new HashMap<>();
        int span = 0;
        for (Task task : byId.values()) {
            span = Math.max(span, endDay(task.id(), byId, endDays, new HashSet<>()));
        }
        return span;
    }

    private static int endDay(long id, Map<Long, Task> byId, Map<Long, Integer> endDays,
                              Set<Long> resolving) {
        Integer cached = endDays.get(id);
        if (cached != null) {
            return cached;
        }
        Task task = byId.get(id);
        if (task == null) {
            return 0;
        }

        int startDay = 1;
        Long parentId = task.dependsOnStepId();
        /*
          `resolving` is the chain currently being walked, so a dependency
          that leads back into it starts on day 1 instead of recursing for
          ever. The service refuses a cycle on every edit that could create
          one — which is exactly why the guard is cheap to keep here rather
          than trusting that it held.
        */
        resolving.add(id);
        if (parentId != null && byId.containsKey(parentId) && !resolving.contains(parentId)) {
            startDay = endDay(parentId, byId, endDays, resolving) + 1;
        }
        resolving.remove(id);

        // The column is CHECK-constrained to at least 1; the clamp is so a row
        // that got past it produces a short task rather than a range that runs
        // backwards.
        int endDay = startDay + Math.max(1, task.tatDays()) - 1;
        endDays.put(id, endDay);
        return endDay;
    }
}
