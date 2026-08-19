import { format, parseISO } from 'date-fns'

import type { JourneyRow } from '@/api/generated/model/journeyRow'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableContainer, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { cn } from '@/lib/utils'

import { titleCase } from '../stageDisplay'
import { formatDuration, formatEffortHrs, isQueueBound } from './journeyFormat'
import { cycleElapsedMins, rollupByResource } from './journeyRollup'

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
 * ## The idle column is the point of the tab — C-056
 *
 * `idleMins = durationMins − Σ effort in that stage, iteration and cycle`,
 * computed server-side. §4A.4: "a stage with 2 days duration but 2 hours of
 * effort is a **queue problem**, not a capacity problem — and that single
 * insight is usually worth the whole ribbon feature."
 *
 * A row where waiting dominates is marked in warning colour, with the reason
 * given to screen readers rather than left to the colour alone. It is a reading
 * aid, not a status: nothing else in the product turns on the threshold, and
 * `isQueueBound` says so where it is defined.
 *
 * ## The roll-up band — C-057
 *
 * §4A.4's footer: effort per person, the cycle's total, and every cycle's. It
 * is a `tfoot` so a screen reader announces it as a summary of the table rather
 * than as five more hops. `RollupFooter`'s own note explains why the all-cycles
 * row carries no elapsed figure.
 */
export function JourneyTab({
  rows,
  cycleTotalHrs,
  allCyclesTotalHrs,
  isLoading,
  loadError,
}: {
  rows: JourneyRow[]
  /** Server figures, not summed here — see `journeyRollup`'s note on why. */
  cycleTotalHrs?: number
  allCyclesTotalHrs?: number
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
          took, how much effort was logged inside it, and how much of it was spent waiting.
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
            <TableHead scope="col" className="text-right">
              Idle
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((row, index) => (
            <JourneyGridRow key={rowKey(row, index)} row={row} />
          ))}
        </TableBody>
        <RollupFooter rows={rows} cycleTotalHrs={cycleTotalHrs} allCyclesTotalHrs={allCyclesTotalHrs} />
      </Table>
    </TableContainer>
  )
}

/**
 * C-057 · §4A.4's roll-up band: effort per person, then the cycle's total, then
 * every cycle's.
 *
 * <p>A `tfoot`, not more `tbody` rows. It is a summary of the table above it,
 * and saying so in markup is what lets a screen reader announce it as one.
 *
 * <p><b>The all-cycles row shows effort and no elapsed figure, deliberately.</b>
 * `GET /journey` returns one cycle's hops, so no elapsed total across every
 * cycle exists to show — and summing this cycle's and labelling it "all cycles"
 * would be a plainly wrong number in the most authoritative-looking row on the
 * page. §4A.4's mock-up does print one; producing it needs a contract change,
 * which is a task and not a side-effect of this one. The cell says "—" and the
 * label says what it covers.
 */
function RollupFooter({
  rows,
  cycleTotalHrs,
  allCyclesTotalHrs,
}: {
  rows: JourneyRow[]
  cycleTotalHrs?: number
  allCyclesTotalHrs?: number
}) {
  const people = rollupByResource(rows)
  const elapsed = cycleElapsedMins(rows)
  // Taken from the rows the server returned rather than from the page's
  // `?cycle=`, which may be absent when the default (current) cycle is showing.
  // The footer only renders when there is at least one hop.
  const cycleNo = rows[0]?.cycleNo

  return (
    <tfoot className="border-t-2 border-border">
      <TableRow>
        <TableCell colSpan={6} className="text-caption font-medium uppercase tracking-wide text-content-muted">
          Roll-up by resource
        </TableCell>
        <TableCell className="text-right text-caption text-content-muted">Elapsed</TableCell>
        <TableCell className="text-right text-caption text-content-muted">Effort</TableCell>
        <TableCell />
      </TableRow>

      {people.map((person) => (
        <TableRow key={person.id}>
          <TableCell colSpan={6}>
            <span className="font-medium text-content">{person.displayName}</span>
            {person.role && <span className="text-content-muted"> ({person.role})</span>}
            <span className="ml-2 text-caption text-content-muted">{describeSpread(person.stages, person.iterations)}</span>
          </TableCell>
          <TableCell className="text-right tabular-nums">{formatDuration(person.elapsedMins)}</TableCell>
          <TableCell className="text-right tabular-nums">{formatEffortHrs(person.effortHrs)}</TableCell>
          <TableCell />
        </TableRow>
      ))}

      <TableRow>
        <TableCell colSpan={6} className="font-medium text-content">
          Total{cycleNo == null ? '' : ` (cycle ${cycleNo})`}
        </TableCell>
        <TableCell className="text-right font-medium tabular-nums">{formatDuration(elapsed)}</TableCell>
        <TableCell className="text-right font-medium tabular-nums">{formatEffortHrs(cycleTotalHrs)}</TableCell>
        <TableCell />
      </TableRow>

      <TableRow>
        <TableCell colSpan={6} className="font-medium text-content">
          Total (all cycles)
        </TableCell>
        <TableCell className="text-right tabular-nums text-content-muted" title="Only this cycle's hops are loaded">
          —
        </TableCell>
        <TableCell className="text-right font-medium tabular-nums">{formatEffortHrs(allCyclesTotalHrs)}</TableCell>
        <TableCell />
      </TableRow>
    </tfoot>
  )
}

/** `3 stages, 2 iterations` — §4A.4's own phrasing, and singular where it should be. */
function describeSpread(stages: number, iterations: number): string {
  const stagePart = `${stages} ${stages === 1 ? 'stage' : 'stages'}`
  if (iterations <= 1) return stagePart
  return `${stagePart}, ${iterations} iterations`
}

function JourneyGridRow({ row }: { row: JourneyRow }) {
  const open = row.exitedAt == null
  const queueBound = isQueueBound(row.durationMins, row.idleMins)

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
      <TableCell
        className={cn(
          'text-right tabular-nums',
          queueBound && 'font-medium text-warning-text',
        )}
      >
        {formatDuration(row.idleMins)}
        {queueBound && (
          <span className="sr-only"> — most of this stage was spent waiting, not working</span>
        )}
      </TableCell>
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
