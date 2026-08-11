import * as React from 'react'
import { Link } from 'react-router-dom'
import { LayoutGrid, List as ListIcon } from 'lucide-react'

import { useListTickets } from '@/api/generated/tickets/tickets'
import { useGetMe } from '@/api/generated/auth/auth'

import { Skeleton } from '@/components/ui/skeleton'
import { EmptyState } from '@/components/ui/empty-state'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

import { groupTickets } from './groupTickets'
import { MyTasksGroupedList } from './MyTasksGroupedList'
import { MyTasksKanbanBoard } from './MyTasksKanbanBoard'

type View = 'list' | 'kanban'

const VIEW_STORAGE_KEY = 'edutrack.myTasks.view.v1'

// A developer's own worklist is bounded by nature — one person cannot be the
// assignee of thousands of open tickets — so this fetches one generous page
// rather than building pager UI the prototype's own S-18 screen never draws.
// `meta.hasMore` still gets a visible note rather than a silent truncation.
const FETCH_LIMIT = 100

function loadView(): View {
  if (typeof window === 'undefined') return 'list'
  return window.localStorage.getItem(VIEW_STORAGE_KEY) === 'kanban' ? 'kanban' : 'list'
}

/**
 * S-18 My Tasks (Developer's home) — C-018.
 *
 * Hard-scoped to `assigneeId = me` via an explicit filter, not just the
 * default row-scope guard: for a Developer the two already coincide
 * (`ScopeResolver`'s default case is `assigned_to = me`), but for PM and
 * Admin — whose normal `GET /tickets` scope is every project, or everything
 * — "my tasks" has to mean their own assignments specifically, same
 * distinction C-015's "My Open" saved view already draws.
 */
export function MyTasksPage() {
  const { data: me } = useGetMe()
  const myUserId = me?.data.id ?? null
  const [view, setView] = React.useState<View>(loadView)

  React.useEffect(() => {
    window.localStorage.setItem(VIEW_STORAGE_KEY, view)
  }, [view])

  const { data, isPending, isError, error, refetch } = useListTickets(
    { assigneeId: myUserId ?? undefined, excludeClosed: true, limit: FETCH_LIMIT },
    { query: { enabled: myUserId != null } },
  )

  const tickets = data?.data ?? []
  const groups = groupTickets(tickets)
  const overdueCount = groups.find((g) => g.key === 'overdue')?.tickets.length ?? 0
  const dueTodayCount = groups.find((g) => g.key === 'dueToday')?.tickets.length ?? 0

  return (
    <div className="flex h-full flex-col gap-4 p-6">
      <div className="flex flex-wrap items-center gap-3">
        <div>
          <h1 className="text-h1 text-content">Your work{me?.data.displayName ? `, ${me.data.displayName.split(' ')[0]}` : ''}</h1>
          <p className="mt-1 text-sm text-content-muted" role="status">
            {tickets.length === 0
              ? 'Nothing open right now.'
              : `${tickets.length} open · ${overdueCount} overdue · ${dueTodayCount} due today`}
          </p>
        </div>

        <div className="ml-auto flex items-center gap-1 rounded-control border border-border bg-surface p-1">
          <Button
            type="button"
            size="sm"
            variant={view === 'list' ? 'secondary' : 'ghost'}
            aria-pressed={view === 'list'}
            onClick={() => setView('list')}
          >
            <ListIcon className="h-4 w-4" />
            List
          </Button>
          <Button
            type="button"
            size="sm"
            variant={view === 'kanban' ? 'secondary' : 'ghost'}
            aria-pressed={view === 'kanban'}
            onClick={() => setView('kanban')}
          >
            <LayoutGrid className="h-4 w-4" />
            Kanban
          </Button>
        </div>
      </div>

      {data?.meta?.hasMore && (
        <p className="rounded-control bg-subtle px-3 py-2 text-caption text-content-muted">
          Showing the first {FETCH_LIMIT} tickets.{' '}
          <Link to={`/tickets?assigneeId=${myUserId}&excludeClosed=true`} className="text-primary hover:underline">
            See the rest in Tickets
          </Link>
          .
        </p>
      )}

      <div className={cn('flex-1', view === 'kanban' ? 'overflow-hidden' : 'overflow-y-auto')}>
        {isPending ? (
          <div className="flex flex-col gap-3" aria-busy>
            {Array.from({ length: 4 }, (_, i) => (
              <Skeleton key={i} className="h-16 w-full" />
            ))}
          </div>
        ) : isError ? (
          <EmptyState
            title="Could not load your tasks"
            description={error instanceof Error ? error.message : 'Something went wrong. Try again.'}
            action={
              <Button size="sm" onClick={() => refetch()}>
                Retry
              </Button>
            }
          />
        ) : tickets.length === 0 ? (
          <EmptyState title="Nothing assigned to you right now" description="Tickets assigned to you will show up here, grouped by how soon they're due." />
        ) : view === 'kanban' ? (
          <MyTasksKanbanBoard tickets={tickets} />
        ) : (
          <MyTasksGroupedList groups={groups} />
        )}
      </div>
    </div>
  )
}
