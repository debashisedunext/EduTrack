import type { QueryClient } from '@tanstack/react-query'

import { getGetObJourneyQueryKey } from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { invalidateObDashboard } from '@/features/onboarding/dashboard/obDashboardFreshness'

/**
 * What has to be re-read after anything is written to a task.
 *
 * <h2>Why this is a function and not four calls to `invalidateQueries`</h2>
 *
 * <p>A write to a step is read back by more than one screen, and until this
 * existed every writer invalidated the journey and stopped there — which is
 * the whole of what the project page shows, and none of what My Tasks shows.
 * So answering a row from the queue's popup left the row behind the popup
 * carrying the status dot, the due date and the "1 row came back" note the
 * task had before the press, and the reader's own action was the one thing the
 * screen would not admit had happened.
 *
 * <p>One function, called by every writer, is what keeps the next screen from
 * being forgotten again: a queue added later is added here once rather than in
 * every mutation that could move it.
 */

/**
 * Every My Tasks read, both shapes.
 *
 * <p>Two prefixes rather than one because the queue and the single-task page do
 * not share a first segment — `['/onboarding/my-tasks', params]` against
 * `['/onboarding/my-tasks/1841']` — and React Query matches a prefix element by
 * element, so the shorter key does not cover the longer one. A single
 * `predicate` would do it in one call and would also silently start matching
 * anything else that ever begins with those characters.
 */
const MY_TASKS_LIST_PREFIX = ['/onboarding/my-tasks'] as const

/** The focused page's key is the task's own path, so it is matched by prefix. */
const myTaskKeyPrefix = (taskId: number) => [`/onboarding/my-tasks/${taskId}`] as const

/**
 * Re-read the journey, every queue that lists a task from it, and the board.
 *
 * <p>`taskId` narrows the second invalidation to the one task's own page where
 * the caller knows which task moved; without it the list alone is refreshed,
 * which is all a caller that moved several rows can honestly say.
 *
 * <p>The dashboard is the third screen this file exists to stop forgetting.
 * OB-02's review cards count the reader's own verifications, so approving or
 * sending back a row is exactly the action that moves them, and until this
 * call was here the cached answer outlived the press. Invalidating is not
 * fetching — nothing is requested for a board that is not mounted — so it
 * costs a caller that happened not to move a figure nothing.
 *
 * <p>It is an invalidation, not a promise of a new number: the review counters
 * are pre-aggregated and the refetch returns the same figures until the stats
 * worker's next pass writes new ones.
 */
export function invalidateAfterTaskWrite(
  queryClient: QueryClient,
  journeyId: number,
  taskId?: number,
) {
  void queryClient.invalidateQueries({ queryKey: getGetObJourneyQueryKey(journeyId) })
  void queryClient.invalidateQueries({ queryKey: MY_TASKS_LIST_PREFIX })
  if (taskId != null) {
    void queryClient.invalidateQueries({ queryKey: myTaskKeyPrefix(taskId) })
  }
  invalidateObDashboard(queryClient)
}
