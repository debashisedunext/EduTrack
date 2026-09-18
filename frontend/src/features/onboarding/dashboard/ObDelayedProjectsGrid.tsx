import { useNavigate } from 'react-router-dom'
import { format, parseISO } from 'date-fns'

import type { ObProjectBoardRow } from '@/api/generated/model'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'

import { behindSchedule, bucketLook, latenessOf } from './obProjectBoard'

/**
 * B-128 · plan §9's first grid — every project running behind its own
 * completion date.
 *
 * <h2>Behind schedule means the project's own date has passed</h2>
 *
 * <p>That is the definition this grid answers to, and it is the one the rest
 * of the board already uses: `DELAYED` (up to seven working days past the
 * completion date) and `AT_RISK` (more than seven) — the two buckets the band
 * counts as Overdue and At risk, and the two the Summary lists draw. So the
 * grid, the cards and the lists are the same projects, and a reader moving
 * between them is not quietly shown three different populations.
 *
 * <p>It used to answer a different question through
 * `GET /onboarding/dashboard/delayed-projects`: every running <em>journey</em>
 * holding an overdue <em>step</em>. Two consequences, both of which this
 * replaces. A project slipping internally while its own finish date still
 * looked fine appeared here and nowhere else; and a project genuinely past its
 * date whose steps were each individually fine did not appear at all. It was
 * also journey-grained, so a client with three slipping services filled three
 * rows of a grid headed "Delayed projects", and the DTO carried no project at
 * all — which is why every row named the client.
 *
 * <h2>Rows come from the board, not from a request of its own</h2>
 *
 * <p>`GET /onboarding/project-board` already returned every running project
 * the caller's scope can see, with the bucket, the date and the lateness on
 * each. Filtering that is one predicate; a second endpoint was a second
 * population that could disagree with the cards above it — which is exactly
 * what it did. The board's `truncated` flag is the page's to surface, and it
 * already does.
 *
 * <h2>Worst first</h2>
 *
 * <p>The grid is worked from the top, so it sorts by how far past the date a
 * project is — `latenessOf`, the same figure every other list on this board
 * orders by.
 */
export interface ObDelayedProjectsGridProps {
  /** Every running project in scope. The behind-schedule ones are cut here. */
  rows: ObProjectBoardRow[]
  isPending: boolean
  isError: boolean
}

export function ObDelayedProjectsGrid({ rows, isPending, isError }: ObDelayedProjectsGridProps) {
  const navigate = useNavigate()
  const behind = rows.filter(behindSchedule).sort((a, b) => latenessOf(b) - latenessOf(a))

  return (
    <section
      aria-labelledby="ob-delayed-projects-heading"
      className="overflow-hidden rounded-card border border-border bg-surface shadow-sm"
    >
      <div className="flex flex-wrap items-center gap-2 px-4 pb-1 pt-3.5">
        <h2
          id="ob-delayed-projects-heading"
          className="text-[11px] font-semibold uppercase tracking-[.08em] text-content-muted"
        >
          Delayed projects
        </h2>
        <span className="text-xs text-content-muted">
          every project past its own completion date, with who holds it
        </span>
      </div>
      <div className="p-4 pt-2">
        {isPending ? (
          <div className="flex flex-col gap-2">
            {Array.from({ length: 4 }, (_, i) => (
              <Skeleton key={i} className="h-12 w-full" />
            ))}
          </div>
        ) : isError ? (
          <EmptyState
            title="The delayed projects grid could not be loaded"
            description="Refresh to try again."
          />
        ) : behind.length === 0 ? (
          <EmptyState
            title="Nothing is behind schedule"
            description="Every running project is on or ahead of its completion date, so there is nothing to work from here."
          />
        ) : (
          <div className="overflow-x-auto rounded-card border border-border">
            <table className="w-full text-left text-sm">
              <caption className="sr-only">
                Projects currently past their completion date, furthest behind first.
              </caption>
              <thead className="text-xs text-content-muted">
                <tr>
                  <th scope="col" className="py-2 pl-3 font-medium">Project</th>
                  <th scope="col" className="py-2 font-medium">Client</th>
                  <th scope="col" className="py-2 font-medium whitespace-nowrap">Start date</th>
                  <th scope="col" className="py-2 font-medium">Module</th>
                  <th scope="col" className="py-2 font-medium">Current stage</th>
                  <th scope="col" className="py-2 font-medium">Responsible</th>
                  <th scope="col" className="py-2 font-medium whitespace-nowrap">
                    Expected completion
                  </th>
                  <th scope="col" className="py-2 pr-3 font-medium whitespace-nowrap">Behind by</th>
                </tr>
              </thead>
              <tbody>
                {behind.map((row) => (
                  <DelayedProjectRow
                    key={row.id}
                    row={row}
                    onOpen={() => navigate(`/onboarding/projects/${row.id}`)}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  )
}

function DelayedProjectRow({ row, onOpen }: { row: ObProjectBoardRow; onOpen: () => void }) {
  const days = latenessOf(row)
  const look = bucketLook(row.bucket)

  return (
    <tr
      tabIndex={0}
      onClick={onOpen}
      onKeyDown={(e) => {
        if (e.key === 'Enter') onOpen()
      }}
      className="cursor-pointer border-t border-border align-top hover:bg-subtle
                 focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2
                 focus-visible:outline-primary"
    >
      {/* The project names the row now, and the client qualifies it in the
          column beside — the grid is headed "Delayed projects" and counted in
          them, and a client with three engagements otherwise put one name on
          three rows with nothing to tell them apart. */}
      <td className="py-2 pl-3 pr-3 font-medium text-content">{row.name}</td>
      <td className="py-2 pr-3 text-content-muted">{row.client.name}</td>
      <td className="py-2 pr-3 whitespace-nowrap text-content-muted">
        <time dateTime={row.startDate}>{format(parseISO(row.startDate), 'd MMM yyyy')}</time>
      </td>
      <td className="py-2 pr-3 text-content-muted">{row.product.name}</td>
      <td className="py-2 pr-3 text-content-muted">{row.currentStage ?? '—'}</td>
      <td className="py-2 pr-3 text-content-muted">{row.implementor?.displayName ?? '—'}</td>
      <td className="py-2 pr-3 whitespace-nowrap text-content-muted">
        {row.tentativeCompletion ? (
          <time dateTime={row.tentativeCompletion}>
            {format(parseISO(row.tentativeCompletion), 'd MMM yyyy')}
          </time>
        ) : (
          '—'
        )}
      </td>
      <td className="py-2 pr-3 whitespace-nowrap">
        {/* `AT_RISK` is the worse of the two — more than seven working days
            past the date — so it takes the heavier chip regardless of how the
            day count rounds. */}
        <Chip variant={row.bucket === 'AT_RISK' ? 'critical' : 'high'}>
          {days >= 1 ? `${days} ${days === 1 ? 'day' : 'days'}` : look.label}
        </Chip>
      </td>
    </tr>
  )
}
