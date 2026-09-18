import { useNavigate } from 'react-router-dom'

import type { ObProjectBoard } from '@/api/generated/model'
import { Skeleton } from '@/components/ui/skeleton'

import { ObProjectDonut } from './ObProjectDonut'
import { peopleSlices, scheduleSlices, type Slice } from './obProjectBoard'

/**
 * OB-02's first band — the three donuts.
 *
 * <h2>One request behind all three</h2>
 *
 * Schedule health, salesperson and implementor are three cuts of the same
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
}

export function ObProjectChartRow({ board, isPending }: ObProjectChartRowProps) {
  const navigate = useNavigate()

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
   * A slice keyed `u<id>` is one person; `others` and `unassigned` are not, and
   * neither is a filter the grid can express. They open the unfiltered grid
   * rather than a wrong one.
   */
  const openPeople = (parameter: 'implementorId' | 'salesPersonId') => (slice: Slice) => {
    const userId = slice.key.startsWith('u') ? slice.key.slice(1) : null
    const query = new URLSearchParams({ status: 'RUNNING' })
    if (userId) query.set(parameter, userId)
    navigate(`/onboarding/projects?${query.toString()}`)
  }

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
      <ObProjectDonut
        title="Schedule health"
        caption="every running project against its own completion date"
        centreLabel="ongoing"
        entryNoun="Status"
        slices={scheduleSlices(rows)}
      />
      <ObProjectDonut
        title="By salesperson"
        caption="who sold each running project"
        centreLabel="projects"
        entryNoun="Salesperson"
        slices={peopleSlices(rows, 'salesPerson')}
        onSelect={openPeople('salesPersonId')}
      />
      <ObProjectDonut
        title="By implementor"
        caption="who is delivering each running project"
        centreLabel="projects"
        entryNoun="Implementor"
        slices={peopleSlices(rows, 'implementor')}
        onSelect={openPeople('implementorId')}
      />
    </div>
  )
}
