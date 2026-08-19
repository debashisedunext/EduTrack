import * as React from 'react'
import { ChevronDown, ChevronRight } from 'lucide-react'

import type { HistoryEntry } from '@/api/generated/model/historyEntry'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'

import { HistoryEntryRow } from './HistoryEntryRow'

/**
 * The History tab — C-059, blueprint §7: "cycle-grouped grid … expandable to
 * show every field change … and every handoff", with comments interleaved
 * (§4B.5) behind an opt-in toggle rather than always on, since a busy
 * ticket's field-change trail and its conversation are each long enough that
 * showing both by default is the noisier default.
 *
 * ## Grouped by cycle, most recent first
 *
 * Every other list in this feature is ordered for what it is — comments
 * oldest-first because a thread is read top to bottom, the ticket list
 * newest-first because it is a work queue. A history tab is a record someone
 * opens to find out what just happened, so the most recent cycle leads, and
 * **within** a cycle the stream stays chronological (oldest hop first) —
 * `TicketHistoryService`'s own order, "the order the chain was built in".
 *
 * Only the latest cycle starts open. A ticket reopened three times otherwise
 * greets the reader with three cycles' worth of stage hops before they reach
 * anything that happened this week.
 */
export function HistoryTab({
  entries,
  isLoading,
  loadError,
  includeComments,
  onIncludeCommentsChange,
  hasMore,
  isLoadingMore,
  onLoadMore,
}: {
  entries: HistoryEntry[]
  isLoading: boolean
  loadError: string | null
  includeComments: boolean
  onIncludeCommentsChange: (value: boolean) => void
  hasMore: boolean
  isLoadingMore: boolean
  onLoadMore: () => void
}) {
  const groups = React.useMemo(() => groupByCycle(entries), [entries])
  const latestCycle = groups[0]?.cycleNo

  return (
    <div className="flex flex-col gap-4">
      <label className="flex w-fit items-center gap-2 text-caption text-content-muted">
        <input
          type="checkbox"
          checked={includeComments}
          onChange={(event) => onIncludeCommentsChange(event.target.checked)}
          className="h-4 w-4 rounded-control border-border"
        />
        Show comments in this stream
      </label>

      {isLoading ? (
        <div className="flex flex-col gap-3" aria-busy="true">
          <Skeleton className="h-16 w-full" />
          <Skeleton className="h-16 w-full" />
        </div>
      ) : loadError ? (
        <p role="alert" className="text-caption text-danger-text">
          {loadError}
        </p>
      ) : groups.length === 0 ? (
        <EmptyState
          title="No history yet"
          description="Field changes and handoffs will appear here as the ticket moves."
        />
      ) : (
        <>
          {groups.map((group) => (
            <CycleGroup key={group.cycleNo} group={group} defaultOpen={group.cycleNo === latestCycle} />
          ))}
          {hasMore && (
            <Button size="sm" variant="secondary" onClick={onLoadMore} disabled={isLoadingMore}>
              {isLoadingMore ? 'Loading…' : 'Load more'}
            </Button>
          )}
        </>
      )}
    </div>
  )
}

interface Group {
  cycleNo: number
  entries: HistoryEntry[]
}

/**
 * Undefined `cycleNo` — a few events predate a ticket's first cycle
 * (`TicketHistory.cycleNo`'s own javadoc) — buckets under a labelled 0 rather
 * than being dropped. A row is never hidden by grouping.
 */
function groupByCycle(entries: HistoryEntry[]): Group[] {
  const byCycle = new Map<number, HistoryEntry[]>()
  for (const entry of entries) {
    const cycleNo = entry.cycleNo ?? 0
    const bucket = byCycle.get(cycleNo)
    if (bucket) bucket.push(entry)
    else byCycle.set(cycleNo, [entry])
  }
  return [...byCycle.entries()]
    .sort((a, b) => b[0] - a[0])
    .map(([cycleNo, groupEntries]) => ({ cycleNo, entries: groupEntries }))
}

function CycleGroup({ group, defaultOpen }: { group: Group; defaultOpen: boolean }) {
  const [open, setOpen] = React.useState(defaultOpen)
  const headingId = `history-cycle-${group.cycleNo}-heading`

  return (
    <section className="rounded-card border border-border bg-surface" aria-labelledby={headingId}>
      <button
        type="button"
        id={headingId}
        aria-expanded={open}
        onClick={() => setOpen((prev) => !prev)}
        className={cn(
          'flex w-full items-center gap-2 rounded-card px-3 py-2 text-left text-body font-medium text-content',
          'hover:bg-subtle focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary',
        )}
      >
        {open ? (
          <ChevronDown aria-hidden="true" className="h-4 w-4 shrink-0" />
        ) : (
          <ChevronRight aria-hidden="true" className="h-4 w-4 shrink-0" />
        )}
        {group.cycleNo === 0 ? 'Before cycle 1' : `Cycle ${group.cycleNo}`}
        <span className="ml-auto text-caption font-normal text-content-muted">
          {group.entries.length} {group.entries.length === 1 ? 'entry' : 'entries'}
        </span>
      </button>

      {open && (
        <ol aria-label={group.cycleNo === 0 ? 'Before cycle 1' : `Cycle ${group.cycleNo}`} className="divide-y divide-border border-t border-border px-3">
          {group.entries.map((entry) => (
            <HistoryEntryRow key={entry.id} entry={entry} />
          ))}
        </ol>
      )}
    </section>
  )
}
