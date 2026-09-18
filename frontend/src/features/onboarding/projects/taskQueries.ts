import type { QueryClient } from '@tanstack/react-query'

import { getGetObJourneyQueryKey } from '@/api/generated/onboarding-journeys/onboarding-journeys'

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
 * Re-read the journey and every queue that lists a task from it.
 *
 * <p>`taskId` narrows the second invalidation to the one task's own page where
 * the caller knows which task moved; without it the list alone is refreshed,
 * which is all a caller that moved several rows can honestly say.
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
}
