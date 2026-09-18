import type { ObProjectBoard, ObProjectBoardRow } from '@/api/generated/model'
import { Skeleton } from '@/components/ui/skeleton'

import { ObProjectDonut } from './ObProjectDonut'
import { peopleSlices, scheduleSlices, type Slice } from './obProjectBoard'

/**
 * OB-02's first band — the three donuts.
 *
 * <h2>One request behind all three</h2>
 *
 * Project Schedule health, salesperson and implementor are three cuts of the same
 * `projects` array, grouped client-side. Not three endpoints: the totals would
 * then be three separate reads that can disagree, and the screen's whole claim
 * is that a slice, a card and a list are the same projects.
 *
 * <h2>Clicking a slice opens the Projects grid, filtered</h2>
 *
 * The grid already accepts `implementorId`, `salesPersonId` and `status`, and
 * it is the screen built to work a list of projects from. Sending a reader
 * there beats a slide-over that would re-list rows this page already holds —
 * and it means the link survives a refresh and can be pasted to a colleague.
 *
 * The schedule donut is the exception and is deliberately not clickable: its
 * buckets are working-calendar arithmetic computed on this response, and the
 * grid has no filter that expresses "more than 7 working days past the
 * completion date". A button that landed on a list quietly meaning something
 * else would be worse than no button — the Delayed projects tab is what
 * answers that question, and the Summary lists below already carry the rows.
 */
export interface ObProjectChartRowProps {
  board?: ObProjectBoard
  isPending: boolean
  /**
   * What a slice opens: the projects behind it, in the board's own panel.
   *
   * <p>A slice used to leave for the Projects grid, filtered by the person.
   * The grid could express two of the three donuts and not the schedule one,
   * so one chart was unclickable and the other two navigated away from the
   * board. All three open the panel now, and the schedule donut is clickable
   * with them — its rows are on this response like everybody else's, and it
   * was only ever the *grid* that had no filter for them.
   */
  onSelectSlice?: (title: string, rows: ObProjectBoardRow[]) => void
  /**
   * Drawn where "By salesperson" is, when the reader is an implementor or
   * their manager. Who sold a project is a management cut neither of them
   * acts on; their own queue is what nothing else on this board showed them.
   * A node rather than a flag, so this row keeps knowing nothing about roles.
   */
  mine?: React.ReactNode
  /**
   * Drawn where "By implementor" is, when the reader is an implementor or
   * their manager. That donut is who across the team is delivering what — a
   * management cut, and the same reason "By salesperson" gives way to their
   * own queue. What takes its place is their review state, which was a row
   * above the charts competing with them for the first screen.
   */
  third?: React.ReactNode
}

export function ObProjectChartRow({
  board,
  isPending,
  onSelectSlice,
  mine,
  third,
}: ObProjectChartRowProps) {

  if (isPending || !board) {
    return (
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        {Array.from({ length: 3 }, (_, index) => (
          <Skeleton key={index} className="h-[300px] w-full rounded-card" />
        ))}
      </div>
    )
  }

  const rows = board.projects

  /**
   * Every slice answers with its own rows, which it already carries — so
   * `others` and `unassigned` need no special case any more. They were the
   * awkward ones while this navigated: neither is a filter the Projects grid
   * can express, so both opened the unfiltered grid rather than a wrong one.
   */
  const openSlice = (slice: Slice) => onSelectSlice?.(slice.label, slice.rows)

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
      <ObProjectDonut
        title="Project Schedule health"
        caption="against each completion date"
        centreLabel="ongoing"
        entryNoun="Status"
        slices={scheduleSlices(rows)}
        onSelect={openSlice}
      />
      {mine ?? (
        <ObProjectDonut
          title="By salesperson"
          caption="who sold each project"
          centreLabel="projects"
          entryNoun="Salesperson"
          slices={peopleSlices(rows, 'salesPerson')}
          onSelect={openSlice}
        />
      )}
      {third ?? (
        <ObProjectDonut
          title="By implementor"
          caption="who is delivering each project"
          centreLabel="projects"
          entryNoun="Implementor"
          slices={peopleSlices(rows, 'implementor')}
          onSelect={openSlice}
        />
      )}
    </div>
  )
}
