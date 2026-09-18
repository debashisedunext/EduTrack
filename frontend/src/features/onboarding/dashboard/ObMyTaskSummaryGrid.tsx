import { useNavigate } from 'react-router-dom'

import { useListObMyTasks } from '@/api/generated/onboarding/onboarding'
import type { ObMyTask } from '@/api/generated/model'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDueDate, myTaskStatusLabel } from '@/features/onboarding/mytasks/myTasks'

import { OB_DASHBOARD_QUERY } from './obDashboardFreshness'

/** One page of the queue. The same cap the donut above it reads on. */
const QUEUE_LIMIT = 200

/**
 * The implementor's own tasks, as the one grid under their board.
 *
 * <h2>Why this is the only tab they get</h2>
 *
 * <p>The other four are cross-team readings — every stuck step, every delayed
 * project, every implementor's load. An implementor is not working the book;
 * they are working a queue, and this is it. Their manager keeps the four,
 * because triaging across people is the job.
 *
 * <h2>Ongoing only, by construction</h2>
 *
 * <p>`GET /onboarding/my-tasks` returns the caller's <b>open</b> tasks across
 * every project, and excludes work nobody expects — a project on hold, one
 * not yet boarded. So "for ongoing projects" needs no filter here and
 * completed tasks never arrive.
 *
 * <h2>Ordered by the delivery date</h2>
 *
 * <p>Soonest first, which is the order the work has to be done in. A task
 * with no date yet sorts <b>last</b> rather than first: the contract's own
 * note on `dueAt` — null means the task has not activated, which is the
 * opposite of urgent, and an undated row at the top would push the week's
 * real deadlines down the page.
 */
export function ObMyTaskSummaryGrid() {
  const navigate = useNavigate()
  const { data, isPending, isError } = useListObMyTasks(
    { limit: QUEUE_LIMIT },
    { query: OB_DASHBOARD_QUERY },
  )

  const rows = [...(data?.data ?? [])].sort(byDueDate)
  const truncated = data?.meta?.hasMore ?? false

  return (
    <section
      aria-labelledby="ob-my-task-summary-heading"
      className="overflow-hidden rounded-card border border-border bg-surface shadow-sm"
    >
      <div className="flex flex-wrap items-center gap-2 px-4 pb-1 pt-3.5">
        <h2
          id="ob-my-task-summary-heading"
          className="text-[11px] font-semibold uppercase tracking-[.08em] text-content-muted"
        >
          Task summary
        </h2>
        <span className="text-xs text-content-muted">
          every open task assigned to you, soonest first
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
            title="Your task summary could not be loaded"
            description="Refresh to try again."
          />
        ) : rows.length === 0 ? (
          <EmptyState
            title="Nothing is assigned to you"
            description="Every task you own is closed, so there is nothing to work from here."
          />
        ) : (
          <div className="overflow-x-auto rounded-card border border-border">
            <table className="w-full text-left text-sm">
              <caption className="sr-only">
                Your open tasks across every running project, by delivery date.
              </caption>
              <thead className="text-xs text-content-muted">
                <tr>
                  <th scope="col" className="py-2 pl-3 font-medium">Project</th>
                  <th scope="col" className="py-2 font-medium">Step</th>
                  <th scope="col" className="py-2 font-medium">Task</th>
                  <th scope="col" className="py-2 font-medium whitespace-nowrap">Delivery date</th>
                  <th scope="col" className="py-2 pr-3 font-medium">Status</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((task) => (
                  <TaskRow
                    key={task.taskId}
                    task={task}
                    onOpen={() => navigate(`/onboarding/my-tasks/${task.taskId}`)}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}

        {truncated && (
          <p className="mt-2 text-xs text-content-muted">
            Showing your first {QUEUE_LIMIT} tasks, soonest first.
          </p>
        )}
      </div>
    </section>
  )
}

/**
 * Soonest first, undated last — see the header. Ties keep the order the
 * server sent, which is its own sequence rather than an arbitrary one.
 */
function byDueDate(a: ObMyTask, b: ObMyTask): number {
  if (!a.dueAt && !b.dueAt) return 0
  if (!a.dueAt) return 1
  if (!b.dueAt) return -1
  return a.dueAt.localeCompare(b.dueAt)
}

function TaskRow({ task, onOpen }: { task: ObMyTask; onOpen: () => void }) {
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
      {/* The project names the row and the client sits under it, smaller:
          "ERP" says nothing without the school, and a school with three
          engagements needs the project to tell its rows apart. One cell,
          because the pair is what a reader actually uses. */}
      <td className="py-2 pl-3 pr-3">
        <span className="block font-medium text-content">{task.projectName}</span>
        <span className="mt-0.5 block text-[11px] text-content-muted">{task.obClientName}</span>
      </td>
      <td className="py-2 pr-3 text-content-muted">{task.stepName}</td>
      <td className="py-2 pr-3 text-content">{task.taskName}</td>
      <td className="py-2 pr-3 whitespace-nowrap text-content-muted">
        {task.dueAt ? (
          <time dateTime={task.dueAt}>{formatDueDate(task.dueAt)}</time>
        ) : (
          /* Not a dash: the task has not activated, which is a state rather
             than a missing value. */
          <span>Not started yet</span>
        )}
      </td>
      <td className="py-2 pr-3">
        <span className="flex flex-wrap items-center gap-1.5">
          <span className="text-content-muted">{myTaskStatusLabel(task.status)}</span>
          {/* The server's own clock, against the working calendar. A task can
              be in progress and late at once, so this sits beside the status
              rather than replacing it. */}
          {task.isOverdue && <Chip variant="danger">Overdue</Chip>}
        </span>
      </td>
    </tr>
  )
}
