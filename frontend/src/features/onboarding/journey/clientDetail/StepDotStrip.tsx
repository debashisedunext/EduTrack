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

export function StepDotStrip({ steps }: { steps: readonly ObStepDot[] }) {
  if (steps.length === 0) return null

  return (
    <ul role="list" aria-label="Service status" className="flex shrink-0 items-center gap-1">
      {steps.map((dot) => (
        <li key={dot.id}>
          <span
            role="img"
            aria-label={stepDotLabel(dot)}
            title={stepDotLabel(dot)}
            className={cn('block h-2.5 w-2.5 rounded-full border-2', dotClass(dot))}
          />
        </li>
      ))}
    </ul>
  )
}
