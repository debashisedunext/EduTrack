import { format, parseISO } from 'date-fns'

import type { JourneyRow } from '@/api/generated/model/journeyRow'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableContainer, TableHead, TableHeader, TableRow } from '@/components/ui/table'

import { titleCase } from '../stageDisplay'
import { formatDuration, formatEffortHrs } from './journeyFormat'

const DATE_FORMAT = 'd MMM'

/**
 * The Journey tab — C-055, blueprint §4A.4's per-hop grid: iteration, stage,
 * resource, role, in, out, duration, effort.
 *
 * ## One row per hop, in the order they happened
 *
 * Not grouped and not collapsed, unlike `EffortTab`/`HistoryTab`. Those group
 * by cycle because they span every cycle; this grid is already one cycle's
 * journey (`useJourneyTab`'s own note on why), and the whole point of §4A.4 is
 * reading *down* it — a ticket that went `DEV → QA → DEV` shows iteration 1
 * and iteration 2 of the same stage as adjacent rows, and any collapsing would
 * hide exactly the bounce the grid exists to make visible.
 *
 * ## The open hop renders as an em dash, not a zero
 *
 * `exitedAt` and `durationMins` are null for the stage the ticket is sitting in
 * right now. `0m` would read as "took no time"; `—` reads as "still running",
 * which is what it is. `formatDuration` owns that decision so the grid and any
 * later consumer agree.
 *
 * ## What is deliberately not here yet
 *
 * The **idle column** is C-056 and the **per-resource roll-up and totals** are
 * C-057, though `GET /journey` already returns `idleMins`, `perResource` and
 * both totals. Rendering them here would finish two other tasks inside this
 * one and make the PR that much harder to review against §4A.4's own split.
 */
export function JourneyTab({
  rows,
  isLoading,
  loadError,
}: {
  rows: JourneyRow[]
  isLoading: boolean
  loadError: string | null
}) {
  if (isLoading) {
    return (
      <div className="flex flex-col gap-3" aria-busy="true">
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
      </div>
    )
  }

  if (loadError) {
    return (
      <p role="alert" className="text-caption text-danger-text">
        {loadError}
      </p>
    )
  }

  if (rows.length === 0) {
    return (
      <EmptyState
        title="No journey yet"
        description="This cycle has no stage transitions. The grid fills in as the ticket is handed on."
      />
    )
  }

  return (
    <TableContainer>
      <Table>
        <caption className="sr-only">
          Stage-by-stage journey for this cycle: iteration, stage, who held it, when it entered and left, how long it
          took and how much effort was logged.
        </caption>
        <TableHeader>
          <TableRow>
            <TableHead scope="col" className="w-12 text-right">
              It
            </TableHead>
            <TableHead scope="col">Stage</TableHead>
            <TableHead scope="col">Resource</TableHead>
            <TableHead scope="col">Role</TableHead>
            <TableHead scope="col">In</TableHead>
            <TableHead scope="col">Out</TableHead>
            <TableHead scope="col" className="text-right">
              Duration
            </TableHead>
            <TableHead scope="col" className="text-right">
              Effort
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((row, index) => (
            <JourneyGridRow key={rowKey(row, index)} row={row} />
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  )
}

function JourneyGridRow({ row }: { row: JourneyRow }) {
  const open = row.exitedAt == null

  return (
    <TableRow>
      <TableCell className="text-right tabular-nums">{row.iterationNo ?? '—'}</TableCell>
      <TableCell className="whitespace-nowrap font-medium text-content">
        {row.stageCode ? titleCase(row.stageCode) : '—'}
      </TableCell>
      <TableCell className="whitespace-nowrap">{row.resource?.displayName ?? <Unrecorded />}</TableCell>
      <TableCell className="whitespace-nowrap text-content-muted">{row.role ?? '—'}</TableCell>
      <TableCell className="whitespace-nowrap tabular-nums">{formatStamp(row.enteredAt)}</TableCell>
      <TableCell className="whitespace-nowrap tabular-nums">
        {open ? <span className="text-content-muted">in progress</span> : formatStamp(row.exitedAt)}
      </TableCell>
      <TableCell className="text-right tabular-nums">{formatDuration(row.durationMins)}</TableCell>
      <TableCell className="text-right tabular-nums">{formatEffortHrs(row.effortHrs)}</TableCell>
    </TableRow>
  )
}

/**
 * An unassigned hop. §4A.2 lets a ticket fall to a project-level queue when the
 * receiving role has nobody free (C-050), so a row with no resource is a real
 * state rather than missing data, and saying so beats an empty cell.
 */
function Unrecorded() {
  return <span className="text-content-muted">Unassigned</span>
}

function formatStamp(iso: string | null | undefined): string {
  if (!iso) return '—'
  try {
    return format(parseISO(iso), DATE_FORMAT)
  } catch {
    return '—'
  }
}

/**
 * `cycleNo` + `iterationNo` + `stageCode` identifies a hop, but the contract
 * has every field optional, so the index is the fallback rather than the key —
 * two open hops on a malformed payload must not collide into one row.
 */
function rowKey(row: JourneyRow, index: number): string {
  return `${row.cycleNo ?? '?'}-${row.iterationNo ?? '?'}-${row.stageCode ?? '?'}-${index}`
}
