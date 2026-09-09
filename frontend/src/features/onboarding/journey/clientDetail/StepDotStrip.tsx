import type { ObStepDot } from '@/api/generated/model'

import { cn } from '@/lib/utils'
import { stepDotLabel } from './ribbonSteps'

/**
 * C-110 · §9's "RAG step dots" on the collapsed journey strip.
 *
 * ## Not a small ribbon
 *
 * These are drawn from `ObStepDot`, which the contract keeps deliberately
 * thinner than the ribbon's step — no owner, no TAT, no block reason —
 * because the client portal renders this same strip and "a field the portal
 * must never show is a field that must not be in the schema it receives". So
 * this is not `JourneyRibbonSegment` with things hidden by CSS; hiding is a
 * property of the markup, and the portal would still be shipping the data.
 *
 * ## Colour is never the only signal
 *
 * Nine dots in three colours is precisely the chart CLAUDE.md's WCAG line
 * exists to stop. Each dot carries its own `title` and `aria-label` naming the
 * service, its state and its colour in words, and the strip is a `list` so a
 * screen-reader user gets them as an enumerable set rather than a run of
 * unlabelled graphics. A skipped step is also drawn hollow and a pending one
 * outlined, so the three states that are not "running" are distinguishable
 * with no colour at all.
 */
const DOT: Record<string, string> = {
  GREEN: 'bg-success border-success',
  AMBER: 'bg-warning border-warning',
  RED: 'bg-danger border-danger',
}

function dotClass(dot: ObStepDot): string {
  if (dot.status === 'DONE') return 'bg-ribbon-done border-ribbon-done'
  if (dot.status === 'SKIPPED') return 'bg-transparent border-ribbon-pending border-dotted'
  if (dot.status === 'PENDING') return 'bg-transparent border-ribbon-pending'
  return DOT[dot.rag ?? ''] ?? 'bg-subtle border-border'
}

export interface StepDotStripProps {
  steps: readonly ObStepDot[]
  /**
   * C-126 · steps carrying an open client escalation — a red ring around the
   * dot, on top of whatever colour it already has, until staff resolve it.
   *
   * Additive and optional, defaulting to none: the portal reuses this same
   * component (see the class docstring) and already renders its own
   * escalation state inline per row, so it never passes this prop. OB-05
   * passes it from its own `GET /onboarding/client-escalations` read — see
   * `ObClientDetailPage`'s own note on why that is a second call rather than
   * a field on `ObStepDot` itself.
   */
  openEscalationStepIds?: ReadonlySet<number>
}

export function StepDotStrip({ steps, openEscalationStepIds }: StepDotStripProps) {
  if (steps.length === 0) return null

  return (
    <ul role="list" aria-label="Service status" className="flex shrink-0 items-center gap-1">
      {steps.map((dot) => {
        const isEscalated = openEscalationStepIds?.has(dot.id) ?? false
        const label = isEscalated ? `${stepDotLabel(dot)} — escalated, awaiting staff` : stepDotLabel(dot)
        return (
          <li key={dot.id}>
            <span
              role="img"
              aria-label={label}
              title={label}
              className={cn(
                'block h-2.5 w-2.5 rounded-full border-2',
                dotClass(dot),
                isEscalated && 'ring-2 ring-danger ring-offset-1',
              )}
            />
          </li>
        )
      })}
    </ul>
  )
}
