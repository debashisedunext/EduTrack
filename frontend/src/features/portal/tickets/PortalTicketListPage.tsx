import * as React from 'react'
import { Link } from 'react-router-dom'

import { useListPortalTickets } from '@/api/generated/portal/portal'
import type { PortalTicket } from '@/api/generated/model/portalTicket'
import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'

/**
 * CP-06 · my tickets — the client's own tickets, read-only.
 *
 * `A-127`'s `PortalTicketController`/`PortalTicketService` are the whole
 * backend: `client_id` scoped server-side off `ClientScopeResolver`, no
 * write verb registered anywhere on `/portal/tickets/**` — plan §1's "no
 * client-raised tickets" is a property of the route table here, not a rule
 * this page has to also enforce. There is deliberately no "Raise a ticket"
 * button anywhere on this screen.
 *
 * `excludeClosed` starts true — a customer opening "my tickets" is almost
 * always checking on something open; closed history is one toggle away
 * rather than the default view.
 */
export function PortalTicketListPage() {
  const [excludeClosed, setExcludeClosed] = React.useState(true)
  const [cursor, setCursor] = React.useState<string | undefined>(undefined)
  const [rows, setRows] = React.useState<PortalTicket[]>([])

  const { data, isPending, isError, isFetching } = useListPortalTickets(
    { excludeClosed, cursor, limit: 20 },
    { query: { placeholderData: (previous) => previous } },
  )

  // Reset the accumulated page whenever the filter changes, not whenever the
  // cursor does — a fresh cursor is "load more" continuing the same list, a
  // fresh filter is a different list starting over.
  React.useEffect(() => {
    setRows([])
    setCursor(undefined)
  }, [excludeClosed])

  React.useEffect(() => {
    if (!data) return
    setRows((previous) => (cursor ? [...previous, ...data.data] : data.data))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data])

  if (isPending) {
    return (
      <div className="flex flex-col gap-4">
        <Skeleton className="h-10 w-48 rounded-control" />
        <Skeleton className="h-24 w-full rounded-card" />
        <Skeleton className="h-24 w-full rounded-card" />
      </div>
    )
  }

  if (isError) {
    return (
      <EmptyState
        title="Could not load your tickets"
        description="Something went wrong. Try reloading the page."
      />
    )
  }

  const nextCursor = data?.meta?.nextCursor
  const hasMore = data?.meta?.hasMore ?? false

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-h2 text-content">My tickets</h1>
          <p className="mt-1 text-sm text-content-muted">Track the progress of your own tickets.</p>
        </div>
        <label className="flex items-center gap-2 text-sm text-content-muted">
          <input
            type="checkbox"
            checked={!excludeClosed}
            onChange={(event) => setExcludeClosed(!event.target.checked)}
            className="size-4 rounded border-border"
          />
          Show closed tickets
        </label>
      </div>

      {rows.length === 0 ? (
        <EmptyState
          title={excludeClosed ? 'No open tickets' : 'No tickets yet'}
          description={
            excludeClosed
              ? 'Nothing open right now. Turn on "Show closed tickets" to see your history.'
              : 'Tickets raised on your behalf will show up here.'
          }
        />
      ) : (
        <ul className="flex flex-col divide-y divide-border rounded-card border border-border bg-surface">
          {rows.map((ticket) => (
            <TicketRow key={ticket.id} ticket={ticket} />
          ))}
        </ul>
      )}

      {hasMore && nextCursor ? (
        <div>
          <Button variant="secondary" disabled={isFetching} onClick={() => setCursor(nextCursor)}>
            {isFetching ? 'Loading…' : 'Load more'}
          </Button>
        </div>
      ) : null}
    </div>
  )
}

function TicketRow({ ticket }: { ticket: PortalTicket }) {
  return (
    <li>
      <Link
        to={`/portal/tickets/${ticket.ticketId}`}
        className="flex items-center justify-between gap-3 px-4 py-3 hover:bg-subtle focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
      >
        <div className="flex min-w-0 flex-col">
          <span className="truncate text-sm font-medium text-content">{ticket.title}</span>
          <span className="text-caption text-content-muted">
            {ticket.ticketId} · {ticket.projectName}
            {ticket.taskTypeName ? ` · ${ticket.taskTypeName}` : ''} · Reported{' '}
            {new Date(ticket.dateReported).toLocaleDateString()}
          </span>
        </div>
        <StatusChip status={ticket.status} />
      </Link>
    </li>
  )
}

function StatusChip({ status }: { status: string }) {
  switch (status) {
    case 'CLOSED':
      return <Chip variant="success">Closed</Chip>
    case 'RESOLVED':
      return <Chip variant="success">Resolved</Chip>
    case 'ON_HOLD':
    case 'AWAITING_INFO':
      return <Chip variant="warning">{status === 'ON_HOLD' ? 'On hold' : 'Awaiting info'}</Chip>
    case 'REWORK':
    case 'REOPENED':
      return <Chip variant="danger">{status === 'REWORK' ? 'Rework' : 'Reopened'}</Chip>
    case 'IN_PROGRESS':
      return <Chip variant="info">In progress</Chip>
    default:
      return <Chip variant="neutral">New</Chip>
  }
}

export default PortalTicketListPage
