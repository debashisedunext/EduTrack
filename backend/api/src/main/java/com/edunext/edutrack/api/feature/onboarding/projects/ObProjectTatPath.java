package com.edunext.edutrack.api.feature.onboarding.projects;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A project's TAT, along the longest chain of Module Services rather than
 * across all of them.
 *
 * <h2>Why this replaced a sum</h2>
 *
 * <p>{@code totalTatDays} was {@code SUM(tat_days)} over every task of every
 * journey on the project, and the query that computed it said so:
 *
 * <blockquote>It is a sum, including across parallel tasks, which overstates
 * elapsed time wherever the dependency graph lets two tasks run at once. That
 * is plan §5.10's own convention for a journey total — "the sum of its
 * services' TAT days" — followed here rather than quietly improved on. A
 * critical-path figure is computable from {@code depends_on_step_id} and would
 * be a different number on every screen that prints this one, so it is a
 * decision for the plan rather than for this query.</blockquote>
 *
 * <p>The decision has been taken: a project boarded through two Module Services
 * that do not depend on each other takes as long as the longer of them, not as
 * long as both. Reporting 8 days for two 4-day services that run side by side
 * put a tentative completion date a week late on every parallel project, which
 * is a promise to a client rather than an internal rounding.
 *
 * <h2>What is a chain and what is not</h2>
 *
 * <p>{@code ob_journey_template_dependencies} is the model, and it is the same
 * one {@code ObJourneyDependencyRelease} uses to decide when a journey's tasks
 * may start: a service whose template depends on another's cannot run until
 * that one completes. So the dependency edges are exactly the edges that make
 * two services sequential, and everything else is parallel.
 *
 * <p><b>Within</b> a service the same question is asked by
 * {@link com.edunext.edutrack.api.feature.onboarding.journeys.JourneyTatCalculator},
 * which walks {@code depends_on_step_id} the same way and is what the product
 * catalogue's own figure uses. The repository folds with that first and this
 * second, so a project's TAT and its product's mean the same thing.
 *
 * <p>This is a separate walk rather than a call into it because a task waits on
 * <em>one</em> other task and a service can wait on <em>several</em>: collapsing
 * a set of dependencies to a single parent to reuse that signature would drop
 * every edge but one, which on a service waiting on two others is a figure
 * quietly too low.
 *
 * <h2>The shape of the answer</h2>
 *
 * <p>Longest path through a DAG, weighted by each service's own summed TAT:
 * the cost of a service is its own days plus the costliest chain it waits on,
 * and the project's figure is the costliest service. With no edges at all —
 * every service parallel — that reduces to the maximum, which is the case the
 * screenshot showed.
 */
final class ObProjectTatPath {

    private ObProjectTatPath() {
    }

    /**
     * One service of one project: its own TAT and the services it waits on.
     *
     * @param journeyId  {@code ob_journeys.id}.
     * @param ownTatDays Σ of its own tasks' pinned TAT days.
     * @param dependsOn  Journey ids of this project that must finish first.
     *                   Empty for a service that can start as soon as the gate
     *                   opens.
     */
    record Node(long journeyId, int ownTatDays, Set<Long> dependsOn) {
    }

    /**
     * The project's TAT — the heaviest chain through {@code nodes}.
     *
     * <p>Zero for a project with no journeys, which is what an unstarted
     * project is, and what the old {@code COALESCE(SUM(...), 0)} answered too.
     */
    static int longestPath(Collection<Node> nodes) {
        Map<Long, Node> byId = new LinkedHashMap<>();
        nodes.forEach(n -> byId.put(n.journeyId(), n));

        Map<Long, Integer> memo = new HashMap<>();
        Deque<Long> visiting = new ArrayDeque<>();

        int longest = 0;
        for (Node node : byId.values()) {
            longest = Math.max(longest, cost(node.journeyId(), byId, memo, visiting));
        }
        return longest;
    }

    /**
     * This service's own days plus the costliest chain behind it.
     *
     * <p><b>A cycle contributes nothing rather than looping.</b> The dependency
     * graph should be acyclic — a service that waits on a service that waits on
     * it can never start, and nothing in the module would ever release either —
     * but "should be" is not a guarantee this read can make, and a read that
     * hangs is a worse answer to a bad row than a figure that is merely too
     * low. The edge is skipped and the rest of the graph still computes.
     */
    private static int cost(long journeyId, Map<Long, Node> byId, Map<Long, Integer> memo,
                            Deque<Long> visiting) {
        Integer done = memo.get(journeyId);
        if (done != null) {
            return done;
        }
        Node node = byId.get(journeyId);
        if (node == null) {
            /*
              A dependency on a service this project was not boarded through.
              The edge is on the *template*, so a product whose SIS template
              depends on a Core template says nothing about a project that
              bought SIS alone — there is no journey to wait for, so there is
              nothing to add.
            */
            return 0;
        }
        if (visiting.contains(journeyId)) {
            return 0;
        }

        visiting.push(journeyId);
        int heaviest = 0;
        for (Long dependency : node.dependsOn()) {
            heaviest = Math.max(heaviest, cost(dependency, byId, memo, visiting));
        }
        visiting.pop();

        int total = node.ownTatDays() + heaviest;
        memo.put(journeyId, total);
        return total;
    }
}
