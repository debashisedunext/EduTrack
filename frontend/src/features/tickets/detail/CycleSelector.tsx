import { format, parseISO } from 'date-fns'
import { Lock } from 'lucide-react'

import type { Cycle } from '@/api/generated/model/cycle'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'

/**
 * C-053 · the cycle selector above the ribbon — blueprint §4A: **"each cycle
 * has its own ribbon."** A reopen (C-038) starts a new cycle with its own
 * journey; this is what lets a reader step back into an earlier one.
 *
 * The mechanics already existed before this task: `?cycle=` on the page's own
 * URL re-fetches `GET /tickets/{id}/full`, which is what makes `detail.ribbon`
 * (C-051/C-052) and the "sealed and read-only" banner (C-019) belong to
 * whichever cycle is selected. What was missing was somewhere on screen that
 * *looked like* a selector rather than the summary rail's "Cycles" row, whose
 * links are pointed at the Effort tab specifically (`cycleEffortPath`) and
 * read as a citation, not a switch.
 *
 * **Buttons driven by `setSearchParams`, not `Link`s** — the same choice
 * `TicketDetailTabs` made for `?tab=`, and for the same reason: this is a
 * second on-page view over data the page already fetched, not a citation to
 * paste into chat (the summary rail's row is still that). `TicketDetailPage`
 * owns the callback so switching cycles never disturbs whichever `?tab=` the
 * reader is already on — the Cycles row's links jump to Effort on purpose;
 * this does not.
 *
 * **Hidden entirely below two cycles.** A ticket that has never been reopened
 * has nothing to select between, and a one-item selector reads as a control
 * that does something rather than as the fact that there is only one journey.
 *
 * **The accessible name is "View cycle N", not "Cycle N".** Both the History
 * tab and the Effort tab already have their own per-cycle group toggle
 * carrying the plain name "Cycle N" — a different control, expanding a
 * section rather than switching the page's cycle. The same name on two
 * controls with two different effects is a real ambiguity for anyone
 * navigating by role and name, not only a query collision in a test that
 * renders the whole page (`TicketHistory.test.tsx` found it this way).
 */
export interface CycleSelectorProps {
  cycles?: Cycle[]
  selectedCycleNo: number
  onSelect: (cycleNo: number) => void
}

const DATE_FORMAT = 'd MMM yyyy'

function cycleDate(value?: string | null): string {
  return value ? format(parseISO(value), DATE_FORMAT) : '—'
}

export function CycleSelector({ cycles, selectedCycleNo, onSelect }: CycleSelectorProps) {
  if (!cycles || cycles.length < 2) return null

  const sorted = [...cycles].sort((a, b) => (a.cycleNo ?? 1) - (b.cycleNo ?? 1))

  return (
    <div role="group" aria-label="Cycle" className="flex flex-wrap items-center gap-1.5">
      {sorted.map((cycle) => {
        const cycleNo = cycle.cycleNo ?? 1
        const isSelected = cycleNo === selectedCycleNo

        return (
          <TooltipProvider key={cycleNo} delayDuration={300}>
            <Tooltip>
              <TooltipTrigger asChild>
                <button
                  type="button"
                  onClick={() => onSelect(cycleNo)}
                  aria-label={`View cycle ${cycleNo}`}
                  aria-current={isSelected ? 'true' : undefined}
                  className={cn(
                    'inline-flex items-center gap-1 rounded-chip border px-2.5 py-1 text-xs font-medium transition',
                    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-1',
                    isSelected
                      ? 'border-primary bg-primary-soft text-primary'
                      : 'border-border bg-surface text-content-muted hover:text-content',
                  )}
                >
                  {cycle.isSealed && <Lock className="h-3 w-3" aria-hidden="true" />}
                  Cycle {cycleNo}
                </button>
              </TooltipTrigger>
              <TooltipContent>
                <dl className="grid grid-cols-[auto_1fr] gap-x-2 gap-y-0.5">
                  <dt className="text-content-muted">Started</dt>
                  <dd>{cycleDate(cycle.startedAt)}</dd>
                  <dt className="text-content-muted">{cycle.isSealed ? 'Closed' : 'Status'}</dt>
                  <dd>{cycle.isSealed ? cycleDate(cycle.closedAt) : 'In progress'}</dd>
                  {cycle.reason && (
                    <>
                      <dt className="text-content-muted">Reopened because</dt>
                      <dd>{cycle.reason}</dd>
                    </>
                  )}
                </dl>
              </TooltipContent>
            </Tooltip>
          </TooltipProvider>
        )
      })}
    </div>
  )
}
