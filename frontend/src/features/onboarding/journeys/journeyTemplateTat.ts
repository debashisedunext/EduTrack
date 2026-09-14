import type { ObJourneyTemplateStep } from '@/api/generated/model/obJourneyTemplateStep'
import { buildStepTree } from './journeyTemplateTree'

/**
 * The designer header's total TAT — **the critical path through the tasks,
 * not the sum of them**.
 *
 * <h2>Why the sum was the wrong number</h2>
 *
 * <p>It used to be Σ `tatDays` across every task, matching the plain sum
 * `ObClientReadRepository#journeysOf`'s `totalTatDays` computes for an
 * instantiated journey. That reading answers "how much work does this service
 * carry", which nobody was asking: the number sits beside "Plan runs to day
 * N" under a heading that says **TAT**, and a turnaround time is elapsed
 * time.
 *
 * <p>Two tasks of 1 and 2 days that wait for nothing genuinely both start on
 * day 1, so the service turns round in **2** days, not 3. Chain the second
 * behind the first and it is 3, because now the days really are consecutive.
 * That is the whole rule: **a dependency adds, a parallel branch does not.**
 *
 * <h2>It is the same walk the Schedule column already does</h2>
 *
 * <p>`buildStepTree` places every task on a day range — a root on day 1, a
 * dependent the day after its predecessor ends — and reports `spanDays`, the
 * last day anything ends on. That is the critical path by construction, so
 * this is a read of the existing walk rather than a second arithmetic that
 * could disagree with the bars beside it. When a template holds several
 * independent chains, the longest one is the answer and the shorter ones cost
 * nothing.
 *
 * <p>Still working days, and still no calendar: weekends and holidays are
 * applied exactly once, on the client's journey, through the working calendar.
 */
export function templateTotalTatDays(
  steps: readonly Pick<ObJourneyTemplateStep, 'id' | 'tatDays' | 'dependsOnStepId'>[],
): number {
  return buildStepTree(steps).spanDays
}

export function formatTemplateTotalTatDays(totalDays: number): string {
  return `${totalDays} working day${totalDays === 1 ? '' : 's'}`
}
