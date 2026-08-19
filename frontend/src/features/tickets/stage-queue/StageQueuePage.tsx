import * as React from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { AlertTriangle } from 'lucide-react'

import { useGetStageQueue } from '@/api/generated/ribbon/ribbon'
import { useListWorkflowTemplates } from '@/api/generated/masters/masters'
import { useListProjects } from '@/api/generated/projects/projects'
import { useGetMe } from '@/api/generated/auth/auth'

import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { FilterDropdown } from '@/components/ui/filter-dropdown'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'

import { formatDuration } from '../journey/journeyFormat'
import { LEVEL_VARIANT } from '../list/columns'
import { defaultStageFor, findStage, isBreached, queueStages, queueTitle } from './stageQueue'

/**
 * S-31 Stage Queue / Team Inbox — C-062.
 *
 * Blueprint line 1108: *"each team's own worklist … sorted by time-in-stage
 * descending so the oldest queued item is always on top. This is the landing
 * page for QA and Deployment resources, the way My Tasks is for Developers."*
 * §17 item 12 states why it exists: *"QA and Deployment are queue-driven teams,
 * not assignment-driven ones. Without a shared 'waiting in QA' list, tickets
 * stall between the handoff and someone noticing."*
 *
 * ## What is in the URL, and what is not
 *
 * `stage`, `projectId` and `unassignedOnly` are URL state, C-014's rule — a
 * filtered queue is a link somebody can paste into chat, and "look at the
 * Deployment queue on CRM" is a sentence people say daily. The **cursor is
 * not**: a pasted link should land on the top of the queue, which is the oldest
 * waiting ticket, rather than on whatever page the sender had scrolled to.
 *
 * ## The sort is the server's
 *
 * Rows arrive ordered by time-in-stage descending and are rendered in that
 * order. Re-sorting here would sort *this page* of a cursor-paginated list
 * rather than the queue, and would put the wrong ticket on top with complete
 * confidence — the failure being that it looks exactly like the right one.
 *
 * ## 🔴 The backend endpoint does not exist yet, and its scope is Stream A's call
 *
 * `GET /stages/queue` is in the contract and in the mock; no controller serves
 * it. That is not the interesting part. The interesting part is that
 * `ScopeResolver` gives a Developer, QA or Deployment resource
 * `assigned_to = me` on **every** ticket read, and under that rule this screen
 * returns only what the caller is already holding — the shared list §17 item 12
 * asks for cannot exist. `StageQueueSubscriptionScope` (D-014) already chose
 * project membership for the matching WebSocket room and deferred this decision
 * here in as many words. Deciding it means changing Stream A's guard, including
 * whether a ticket listed here can be opened at all, so it is raised with
 * Shivendra rather than assumed. This screen is built against the mock, which is
 * DEPENDENCIES §6's first rule and how every screen in this stream was built.
 */
const PAGE_SIZE = 50

export function StageQueuePage() {
  const [searchParams, setSearchParams] = useSearchParams()

  const { data: me } = useGetMe()
  const { data: templatesData } = useListWorkflowTemplates()
  const { data: projectsData } = useListProjects({ isActive: true, limit: 200 })

  const stages = React.useMemo(() => queueStages(templatesData?.data), [templatesData])
  const projects = React.useMemo(() => projectsData?.data ?? [], [projectsData])

  const stageParam = searchParams.get('stage')
  // The viewer's own queue when the URL does not name one — the whole point of
  // this being a landing page. Not written into the URL: a redirect on mount
  // would make the back button leave the screen it just entered, and would also
  // freeze one person's default into any link they copied before choosing.
  const selectedStageCode = stageParam ?? defaultStageFor(me?.data.role, stages)
  const selectedStage = findStage(stages, selectedStageCode ?? null)

  const projectId = searchParams.get('projectId')
  const unassignedOnly = searchParams.get('unassignedOnly') === 'true'

  const setParam = React.useCallback(
    (key: string, value: string | null) => {
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          if (value == null || value === '' || value === 'false') next.delete(key)
          else next.set(key, value)
          return next
        },
        { replace: true },
      )
    },
    [setSearchParams],
  )

  const { data, isPending, isError, error, refetch, isFetching } = useGetStageQueue(
    {
      stage: selectedStageCode ?? '',
      projectId: projectId ? Number(projectId) : undefined,
      unassignedOnly: unassignedOnly || undefined,
      limit: PAGE_SIZE,
    },
    // `stage` is required by the contract, so nothing is asked until one is
    // known — otherwise the first render of a cold cache sends `stage=` and
    // earns a 400 that has nothing to do with what the user did.
    { query: { enabled: Boolean(selectedStageCode) } },
  )

  const rows = data?.data ?? []
  const breachedCount = rows.filter(isBreached).length
  const unassignedCount = rows.filter((r) => r.ticket?.assignee == null).length

  return (
    <div className="flex h-full flex-col gap-4 p-6">
      <div className="flex flex-wrap items-start gap-3">
        <div>
          <h1 className="text-h1 text-content">{queueTitle(selectedStage)}</h1>
          <p className="mt-1 text-sm text-content-muted" role="status">
            {isPending
              ? 'Loading the queue…'
              : rows.length === 0
                ? 'Nothing waiting.'
                : `${rows.length} waiting · ${unassignedCount} unassigned · ${breachedCount} past the stage SLA`}
          </p>
        </div>

        <div className="ml-auto flex flex-wrap items-center gap-2">
          <FilterDropdown
            label="Stage"
            options={stages}
            value={selectedStage ?? null}
            onChange={(s) => setParam('stage', s?.stageCode ?? null)}
            getKey={(s) => s.stageCode}
            getLabel={(s) => s.displayName}
            searchable={false}
          />
          <FilterDropdown
            label="Project"
            options={projects}
            value={projects.find((p) => p.id === Number(projectId)) ?? null}
            onChange={(p) => setParam('projectId', p ? String(p.id) : null)}
            getKey={(p) => String(p.id)}
            getLabel={(p) => `${p.projectCode} — ${p.name}`}
            getSearchable={(p) => [p.projectCode, p.name]}
          />
          <label className="flex items-center gap-1.5 rounded-control border border-border bg-surface px-3 py-1.5 text-sm text-content">
            <input
              type="checkbox"
              checked={unassignedOnly}
              onChange={(event) => setParam('unassignedOnly', event.target.checked ? 'true' : null)}
              className="h-3.5 w-3.5 rounded border-border text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
            />
            Unassigned only
          </label>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto">
        {isPending ? (
          <div className="flex flex-col gap-3" aria-busy>
            {Array.from({ length: 5 }, (_, i) => (
              <Skeleton key={i} className="h-14 w-full" />
            ))}
          </div>
        ) : isError ? (
          <EmptyState
            title="Could not load the queue"
            description={error instanceof Error ? error.message : 'Something went wrong. Try again.'}
            action={
              <Button size="sm" onClick={() => refetch()}>
                Retry
              </Button>
            }
          />
        ) : rows.length === 0 ? (
          <EmptyState
            title={`Nothing is waiting in ${selectedStage?.displayName ?? 'this stage'}`}
            description="Tickets handed to this stage appear here, oldest first, so the one rotting longest is always on top."
          />
        ) : (
          <table className="w-full border-collapse text-sm">
            <caption className="sr-only">
              {queueTitle(selectedStage)} — sorted by time in stage, longest first
            </caption>
            <thead className="sticky top-0 bg-surface">
              <tr className="border-b border-border text-left text-caption text-content-muted">
                <th scope="col" className="px-3 py-2 font-medium">ID</th>
                <th scope="col" className="px-3 py-2 font-medium">Description</th>
                <th scope="col" className="px-3 py-2 font-medium">Project</th>
                <th scope="col" className="px-3 py-2 font-medium">Level</th>
                <th scope="col" className="px-3 py-2 font-medium">Held by</th>
                <th scope="col" className="px-3 py-2 text-right font-medium">Time in stage</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => {
                const ticket = row.ticket
                if (!ticket) return null
                const breached = isBreached(row)
                return (
                  <tr
                    key={ticket.ticketId}
                    className={cn(
                      'border-b border-border-subtle',
                      // The queue's own cue, not C-016's row colour: what matters
                      // on this screen is "this one has been sitting too long",
                      // which is a different question from the ticket's level.
                      breached && 'border-l-4 border-l-level-high',
                    )}
                  >
                    <td className="whitespace-nowrap px-3 py-2.5">
                      <Link
                        to={`/tickets/${ticket.ticketId}`}
                        className="font-mono font-medium text-primary tabular-nums hover:underline"
                      >
                        {ticket.ticketId}
                      </Link>
                    </td>
                    <td className="px-3 py-2.5">
                      <span className="block max-w-[32rem] truncate" title={ticket.title}>
                        {ticket.title}
                      </span>
                    </td>
                    <td className="px-3 py-2.5">
                      {ticket.project ? `${ticket.project.projectCode} — ${ticket.project.name}` : '—'}
                    </td>
                    <td className="px-3 py-2.5">
                      <Chip variant={LEVEL_VARIANT[ticket.level]}>{ticket.level}</Chip>
                    </td>
                    <td className="px-3 py-2.5">
                      {ticket.assignee ? (
                        ticket.assignee.displayName
                      ) : (
                        // The row a queue-driven team is looking for: nobody has
                        // picked this up. Said in words rather than left blank,
                        // because a blank cell reads as missing data.
                        <span className="text-content-muted">Unassigned</span>
                      )}
                    </td>
                    <td
                      className={cn(
                        'whitespace-nowrap px-3 py-2.5 text-right tabular-nums',
                        breached && 'text-warning-text',
                      )}
                    >
                      <span className="inline-flex items-center gap-1">
                        {formatDuration(row.timeInStageMins)}
                        {breached && <AlertTriangle className="h-3.5 w-3.5" aria-label="Past the stage SLA" />}
                      </span>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>

      {data?.meta?.hasMore && (
        <p className="rounded-control bg-subtle px-3 py-2 text-caption text-content-muted" role="status">
          Showing the {PAGE_SIZE} that have waited longest.{' '}
          {isFetching ? 'Refreshing…' : 'Clear the top of the queue and the rest moves up.'}
        </p>
      )}
    </div>
  )
}
