import type { ObJourneyTemplateStep } from '@/api/generated/model/obJourneyTemplateStep'

/**
 * C-120 · the designer header and step-list heading's total TAT — Σ
 * `tatDays` across every step of the template, the same plain sum
 * `ObClientReadRepository#journeysOf`'s `totalTatDays` computes for an
 * instantiated journey (`journeyStrip.ts`'s own note), read one stage
 * earlier: before instantiation rather than after.
 *
 * A step inside a parallel group is not netted out. This is the work the
 * template carries, not the shortest path through it — the same reading
 * `totalTatDays` already gives a client's journey, which does not shorten
 * for its own parallel steps either.
 */
export function templateTotalTatDays(steps: Pick<ObJourneyTemplateStep, 'tatDays'>[]): number {
  return steps.reduce((sum, step) => sum + step.tatDays, 0)
}

export function formatTemplateTotalTatDays(totalDays: number): string {
  return `${totalDays} working day${totalDays === 1 ? '' : 's'}`
}
