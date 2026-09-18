import { useListObMyTasks } from '@/api/generated/onboarding/onboarding'
import type { ObMyTask } from '@/api/generated/model'
import { Skeleton } from '@/components/ui/skeleton'

import { OB_DASHBOARD_QUERY } from './obDashboardFreshness'
import { myTaskSlices } from './obMyTaskSlices'
import { ObProjectDonut } from './ObProjectDonut'

/**
 * One page of the caller's queue. The donut needs every open task to be a
 * true count, and this is the most the route will hand over at once; the
 * caption says so when it is not enough, rather than drawing a total that
 * quietly stops at 200.
 */
const QUEUE_LIMIT = 200

/**
 * The implementor's own workload, where an admin sees "By salesperson".
 *
 * <h2>Why it replaces that donut and not another</h2>
 *
 * <p>Who sold a project is a management cut: an implementor does not act on
 * it, and it invites a reading of somebody else's book. What they do act on is
 * their own queue, which nothing else on this board showed them. Schedule
 * health and By implementor stay — both are about delivery, which is their
 * work — so the band still reads left to right as shape, then mine, then the
 * team's.
 *
 * <h2>It counts tasks</h2>
 *
 * <p>Not checklist items: `obMyTaskSlices` carries that argument, along with
 * what each of the four buckets means and why they do not overlap.
 *
 * <h2>Its own request, and a narrow one</h2>
 *
 * <p>`GET /onboarding/my-tasks` is the queue the My tasks screen lists, and it
 * returns the caller's <b>open</b> tasks — so "ongoing" needs no filter here
 * and completed work never arrives. It is a second read on a board that
 * otherwise makes two, and it is the caller's own rows rather than a cut of
 * the project board: a project-board row knows how many tasks a project holds,
 * never which of them are this person's.
 */
export function ObMyTaskDonut() {
  const { data, isPending, isError } = useListObMyTasks(
    { limit: QUEUE_LIMIT },
    { query: OB_DASHBOARD_QUERY },
  )

  if (isPending) return <Skeleton className="h-[300px] w-full rounded-card" />

  const tasks = data?.data ?? []
  const truncated = data?.meta?.hasMore ?? false

  return (
    <ObProjectDonut<ObMyTask>
      title="My tasks"
      caption={
        isError
          ? 'your queue could not be loaded'
          : truncated
            ? `your first ${QUEUE_LIMIT} open tasks`
            : 'your open tasks, every project'
      }
      centreLabel="tasks"
      entryNoun="State"
      unitNoun="tasks"
      slices={myTaskSlices(tasks)}
    />
  )
}
