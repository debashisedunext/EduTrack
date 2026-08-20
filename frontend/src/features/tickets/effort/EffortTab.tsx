import * as React from 'react'
import { ChevronDown, ChevronRight, X } from 'lucide-react'
import { format, parseISO } from 'date-fns'

import type { Cycle } from '@/api/generated/model/cycle'
import type { EffortLog } from '@/api/generated/model/effortLog'
import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableContainer, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { cn } from '@/lib/utils'

import type { SegmentFilter } from '../detail/segmentFilter'
import { matchesSegmentFilter } from '../detail/segmentFilter'
import { cycleEffortHrs, hoursLabel } from '../detail/ticketSummary'

const DATE_FORMAT = 'd MMM yyyy'

/**
 * The Effort tab — C-061, blueprint §7: "every log line: date, resource,
 * hours, description, cycle no. Sum per cycle + grand total."
 *
 * ## Grouped by cycle, most recent first — `HistoryTab`'s own convention
 *
 * A ticket reopened three times should not bury this week's hours under three
 * cycles of old ones, so only the latest cycle starts open. Within a cycle the
 * lines stay chronological, oldest first — the order the append-only log was
 * actually written in.
 *
 * ## The totals are not summed from `entries`
 *
 * `cycleEffortHrs`/`totalEffortHrs` (`ticketSummary.ts`) read `Cycle.totalEffortHrs`
 * — the materialised, C-041-maintained running total the summary panel's
 * "Logged" row and its per-cycle links already trust. Summing `entries`
 * instead would make a cycle's total depend on how many pages of it this tab
 * happens to have fetched, and disagree with the panel the moment "Load more"
 * has not been pressed yet. **`filter` narrows which rows are listed and never
 * this total** — the same reason: a stage filter changing the number beside
 * "Grand total" would disagree with the identical figure on the summary panel
 * a few hundred pixels to the right.
 *
 * ## `filter` — C-052, a click on the ribbon above
 *
 * Client-side, over `entries` already in hand — `listEffortLogs` does carry
 * `stage`/`iteration` query params (`ListEffortLogsParams`), unlike History's
 * endpoint, but re-fetching to narrow rows this tab already has in full would
 * still be the waterfall C-060's `clientVisibleOnly` note declined for
 * Attachments. `matchesSegmentFilter` explains why `cycleNo` is part of the
 * match and not just stage and iteration.
 */
export function EffortTab({
  entries,
  cycles,
  grandTotalHrs,
  isLoading,
  loadError,
  hasMore,
  isLoadingMore,
  onLoadMore,
  filter,
  onClearFilter,
}: {
  entries: EffortLog[]
  /** From the aggregated `/full` payload — every cycle, regardless of `?cycle=`. */
  cycles: Cycle[] | undefined
  grandTotalHrs: number
  isLoading: boolean
  loadError: string | null
  hasMore: boolean
  isLoadingMore: boolean
  onLoadMore: () => void
  /** Narrows to one ribbon segment's stage, iteration and cycle — `undefined` for none. */
  filter?: SegmentFilter
  onClearFilter?: () => void
}) {
  const filtered = React.useMemo(
    () => (filter ? entries.filter((entry) => matchesSegmentFilter(entry, filter)) : entries),
    [entries, filter],
  )
  const groups = React.useMemo(() => groupByCycle(filtered), [filtered])
  const latestCycle = groups[0]?.cycleNo

  return (
    <div className="flex flex-col gap-4">
      {filter && (
        <div className="flex flex-wrap items-center gap-2">
          <Chip variant="info" className="gap-1.5">
            Filtered to {filter.displayName}
            {filter.iterationNo != null && filter.iterationNo > 1 ? ` · Iteration ${filter.iterationNo}` : ''}
            <button
              type="button"
              onClick={onClearFilter}
              className="rounded-full focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
              aria-label={`Clear filter: ${filter.displayName}`}
            >
              <X className="h-3 w-3" />
            </button>
          </Chip>
          <span className="text-caption text-content-muted">Totals below still cover the whole cycle.</span>
        </div>
      )}

      {isLoading ? (
        <div className="flex flex-col gap-3" aria-busy="true">
          <Skeleton className="h-16 w-full" />
          <Skeleton className="h-16 w-full" />
        </div>
      ) : loadError ? (
        <p role="alert" className="text-caption text-danger-text">
          {loadError}
        </p>
      ) : (
        <>
          {/* A filter narrowing every row out of view still leaves a grand
              total to show — it is the whole ticket's materialised figure,
              not a sum of what is currently listed, so an empty filtered
              view does not make it disappear. With no filter, an empty
              ticket shows only the empty state, unchanged from before. */}
          {(groups.length > 0 || filter) && (
            <p className="text-body font-medium text-content">
              Grand total <span className="tabular-nums">{hoursLabel(grandTotalHrs)}</span>
            </p>
          )}

          {groups.length === 0 ? (
            <EmptyState
              title={filter ? `No effort logged for ${filter.displayName}` : 'No effort logged yet'}
              description={
                filter
                  ? 'Nothing was logged against this stage and iteration.'
                  : 'Hours logged against this ticket will appear here, grouped by cycle.'
              }
            />
          ) : (
            <>
              {groups.map((group) => (
                <CycleGroup
                  key={group.cycleNo}
                  group={group}
                  cycleTotalHrs={cycleEffortHrs(cycles, group.cycleNo)}
                  defaultOpen={group.cycleNo === latestCycle}
                />
              ))}

              {hasMore && (
                <Button size="sm" variant="secondary" onClick={onLoadMore} disabled={isLoadingMore}>
                  {isLoadingMore ? 'Loading…' : 'Load more'}
                </Button>
              )}
            </>
          )}
        </>
      )}
    </div>
  )
}

interface Group {
  cycleNo: number
  entries: EffortLog[]
}

/**
 * Undefined `cycleNo` buckets under a labelled 0 — `HistoryTab.groupByCycle`'s
 * identical convention. A row is never hidden by grouping.
 */
function groupByCycle(entries: EffortLog[]): Group[] {
  const byCycle = new Map<number, EffortLog[]>()
  for (const entry of entries) {
    const cycleNo = entry.cycleNo ?? 0
    const bucket = byCycle.get(cycleNo)
    if (bucket) bucket.push(entry)
    else byCycle.set(cycleNo, [entry])
  }
  return [...byCycle.entries()]
    .sort((a, b) => b[0] - a[0])
    .map(([cycleNo, groupEntries]) => ({
      cycleNo,
      entries: [...groupEntries].sort((a, b) => (a.createdAt ?? '').localeCompare(b.createdAt ?? '')),
    }))
}

function CycleGroup({
  group,
  cycleTotalHrs,
  defaultOpen,
}: {
  group: Group
  cycleTotalHrs: number
  defaultOpen: boolean
}) {
  const [open, setOpen] = React.useState(defaultOpen)
  const headingId = `effort-cycle-${group.cycleNo}-heading`
  const label = group.cycleNo === 0 ? 'Before cycle 1' : `Cycle ${group.cycleNo}`

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
        {label}
        <span className="ml-auto text-caption font-normal tabular-nums text-content-muted">
          {hoursLabel(cycleTotalHrs)}
        </span>
      </button>

      {open && (
        <div className="border-t border-border">
          <TableContainer className="rounded-none border-0">
            <Table aria-label={`${label} effort log`}>
              <TableHeader>
                <TableRow>
                  <TableHead>Date</TableHead>
                  <TableHead>Resource</TableHead>
                  <TableHead className="text-right">Hours</TableHead>
                  <TableHead>Description</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {group.entries.map((entry, index) => (
                  <EffortLogRow key={entry.id ?? index} entry={entry} />
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        </div>
      )}
    </section>
  )
}

function EffortLogRow({ entry }: { entry: EffortLog }) {
  return (
    <TableRow>
      <TableCell className="whitespace-nowrap">{formatWorkDate(entry.workDate)}</TableCell>
      <TableCell>{entry.user?.displayName ?? 'Unknown'}</TableCell>
      <TableCell className={cn('text-right tabular-nums', entry.isCorrection && 'italic text-content-muted')}>
        {hoursLabel(entry.hours)}
      </TableCell>
      <TableCell className={cn(entry.isCorrection && 'italic text-content-muted')}>
        {entry.note ?? <span className="text-content-muted">—</span>}
        {entry.isCorrection && <span className="ml-1 text-caption">(correction)</span>}
      </TableCell>
    </TableRow>
  )
}

function formatWorkDate(value: string | undefined): string {
  if (!value) return '—'
  const parsed = parseISO(value)
  if (Number.isNaN(parsed.getTime())) return '—'
  return format(parsed, DATE_FORMAT)
}
