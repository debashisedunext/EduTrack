import { RotateCcw } from 'lucide-react'

import type { Ribbon } from '@/api/generated/model/ribbon'
import { Chip } from '@/components/ui/chip'

/**
 * C-054 · the `Cycle 2 · Iteration 3` readout above the ribbon — blueprint
 * §4A.2's own two-counter table, rendered as the one sentence its own prose
 * gives for reading them together: **"Cycle 2 · Iteration 3 · currently in
 * QA."** `C-053`'s selector answers *which* cycle a reader has stepped into;
 * this answers how far that cycle's own journey has bounced, and neither
 * substitutes for the other — a ticket on its first cycle has nothing to
 * select (`CycleSelector` renders nothing below two cycles) but can still
 * have iterated within it, and that fact would otherwise have no home on the
 * page at all.
 *
 * **Sourced from `Ribbon`, not `Ticket`.** `Ticket.cycleNo`/`iterationNo` are
 * the ticket's *live* counters — right for the current cycle, wrong for a
 * past one, which is exactly the cross-cycle mistake `stream-tickets`'s own
 * warning names. `Ribbon.cycleNo`/`iterationNo` are already scoped to
 * whichever cycle `?cycle=` selected (`TicketDetailService`'s own doc on what
 * that param filters), so reading a sealed cycle 1 after a reopen shows that
 * cycle's own final iteration count, not cycle 2's.
 *
 * **Renders nothing when there is no ribbon to describe.** A ticket with no
 * workflow template has no stage journey (`RibbonStrip`'s own empty state)
 * and no iteration to report either — showing "Iteration 1" over an empty
 * ribbon would assert a journey that was never walked.
 *
 * **The rework count is this cycle's total, not the current segment's.** Each
 * segment already carries its own `↺ ×N` badge (`RibbonSegment`, C-051) for
 * "this stage bounced N times"; summing every segment's `loopBackCount` here
 * answers the different question the wireframe's own "↺ 1 rework" line asks —
 * how much ping-pong this cycle has seen in total, at a glance, without
 * reading every tile.
 */
export interface CycleIterationBadgeProps {
  ribbon?: Ribbon
}

export function CycleIterationBadge({ ribbon }: CycleIterationBadgeProps) {
  if (ribbon?.cycleNo == null) return null

  const cycleNo = ribbon.cycleNo
  const iterationNo = ribbon.iterationNo ?? 1
  const reworkCount = (ribbon.segments ?? []).reduce((sum, segment) => sum + (segment.loopBackCount ?? 0), 0)

  return (
    <Chip variant={iterationNo > 1 ? 'warning' : 'neutral'} className="gap-1.5">
      <span>
        Cycle {cycleNo} · Iteration {iterationNo}
      </span>
      {reworkCount > 0 && (
        <span className="inline-flex items-center gap-0.5">
          <RotateCcw className="h-3 w-3" aria-hidden="true" />
          {reworkCount} {reworkCount === 1 ? 'rework' : 'reworks'}
        </span>
      )}
    </Chip>
  )
}
