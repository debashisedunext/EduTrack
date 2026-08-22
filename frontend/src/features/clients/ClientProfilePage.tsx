import { Link, useParams } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'

import { useGetClient360 } from '@/api/generated/clients/clients'
import type { Ticket } from '@/api/generated/model/ticket'
import { EmptyState } from '@/components/ui/empty-state'
import { ticketPath } from '@/features/tickets/detail/entityLinks'

/**
 * B-066 · S-32's Client 360 view — the read-across a PM opens by clicking a
 * client name on a ticket (S-20's traceability rule, `entityLinks.ts`'s
 * `clientPath`). `CLIENT_ROUTE` has rendered a `ScreenPlaceholder` since
 * C-019; A-069's sibling (the resource 360) shipped, this one did not, and
 * neither had a task until Debashis raised it.
 *
 * <h2>Not a new endpoint to design — one already declared and unmounted</h2>
 *
 * `GET /clients/{clientId}/tickets` has been in the contract, the MSW mock
 * and the generated client since D-001, with the exact shape this page
 * renders: one client, a page of its tickets, and four rolled-up figures.
 * `Client360Service` is the backend half B-066 also had to write — the
 * seventh "declared, mocked, never mounted" gap this stream has closed on
 * this resource's own family of routes.
 *
 * <h2>A sample, not the list — `ResourceProfilePage`'s own rule</h2>
 *
 * Ten rows link out to `/tickets?clientId=`, the real list screen that knows
 * how to page, sort and filter. Embedding a second paginated grid here would
 * be a second implementation of a screen that already exists.
 *
 * <h2>The figures are the caller's own view, not the client's whole book</h2>
 *
 * `openCount`/`closedCount`/`slaCompliancePct`/`avgResolutionHrs` are scoped
 * the same way the ticket list beside them is — a Developer opening this
 * screen sees their own assigned work against the client, not every ticket
 * raised against it. `Client360Service`'s javadoc carries the full argument;
 * this page just renders whatever the server decided was theirs to see.
 */
export function ClientProfilePage() {
  const { clientId = '' } = useParams()
  const id = Number(clientId)

  const view = useGetClient360(id, { limit: 10 })
  const data = view.data?.data

  if (view.isPending) {
    return <ClientProfileSkeleton />
  }

  // 404 covers both "no such client" and a malformed id in the URL — there
  // is no scope ambiguity to preserve here the way there is on the resource
  // profile, because a client is not row-scoped (ClientController's own
  // argument): every authenticated caller already reads every client
  // through the ticket form's dropdown.
  if (view.isError || !data?.client) {
    return (
      <div className="p-6">
        <BackLink />
        <EmptyState title="Client not found" description="No client with this id." />
      </div>
    )
  }

  const client = data.client
  const tickets = data.tickets ?? []

  return (
    <div className="flex flex-col gap-6 p-6">
      <BackLink />

      <header className="rounded-card border border-border bg-surface p-5">
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-xl font-semibold text-content">{client.name}</h1>
          <span className="rounded bg-subtle px-2 py-0.5 font-mono text-sm text-content-muted">
            {client.clientCode}
          </span>
          {client.status && (
            <span className="rounded-chip bg-subtle px-2 py-0.5 text-caption text-content-muted">
              {client.status}
            </span>
          )}
        </div>

        <dl className="mt-3 flex flex-wrap gap-x-6 gap-y-1 text-sm text-content-muted">
          {client.accountManager && (
            <div className="flex gap-1">
              <dt>Account manager</dt>
              <dd className="text-content">{client.accountManager.displayName}</dd>
            </div>
          )}
          {client.supportPlan && (
            <div className="flex gap-1">
              <dt>Support plan</dt>
              <dd className="text-content">{client.supportPlan}</dd>
            </div>
          )}
        </dl>
      </header>

      <section aria-label="Client summary" className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Tile label="Open" value={data.openCount ?? 0} />
        <Tile label="Closed" value={data.closedCount ?? 0} />
        <Tile label="SLA met" value={data.slaCompliancePct} suffix="%" />
        <Tile label="Avg resolution" value={data.avgResolutionHrs} suffix="h" />
      </section>

      <RecentTickets clientId={id} rows={tickets} />
    </div>
  )
}

function RecentTickets({
  clientId,
  rows,
}: {
  clientId: number
  rows: Ticket[] | undefined
}) {
  return (
    <section aria-labelledby="client-tickets" className="rounded-card border border-border bg-surface p-5">
      <div className="flex items-center justify-between">
        <h2 id="client-tickets" className="text-sm font-semibold text-content">
          Tickets
        </h2>
        <Link
          to={`/tickets?clientId=${clientId}`}
          className="text-sm text-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        >
          Open full list
        </Link>
      </div>

      {!rows || rows.length === 0 ? (
        <p className="mt-3 text-sm text-content-muted">No tickets have been raised against this client yet.</p>
      ) : (
        <ul className="mt-3 divide-y divide-border">
          {rows.map((t) => (
            <li key={t.ticketId} className="flex items-center gap-3 py-2 text-sm">
              <Link
                to={ticketPath(t.ticketId)}
                className="font-mono text-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
              >
                {t.ticketId}
              </Link>
              <span className="min-w-0 flex-1 truncate text-content">{t.title}</span>
              <span className="text-content-muted">{t.status}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

/**
 * Renders an em dash for null rather than 0. `Client360Service` sends null
 * when the figure has no answer — nothing closed, so nothing could have been
 * on time — and 0% is a measurement, a different claim. `ResourceProfilePage`'s
 * `Tile` states the same rule for the same reason.
 */
function Tile({ label, value, suffix }: { label: string; value: number | null | undefined; suffix?: string }) {
  return (
    <div className="rounded-control border border-border bg-surface p-3">
      <p className="text-caption text-content-muted">{label}</p>
      <p className="mt-1 text-h3 font-semibold text-content">
        {value === null || value === undefined ? '—' : `${value}${suffix ?? ''}`}
      </p>
    </div>
  )
}

function BackLink() {
  return (
    <Link
      to="/tickets"
      className="inline-flex items-center gap-1 text-sm text-content-muted hover:text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
    >
      <ArrowLeft className="size-4" aria-hidden="true" />
      Back to tickets
    </Link>
  )
}

function ClientProfileSkeleton() {
  return (
    <div className="flex flex-col gap-6 p-6" aria-busy="true">
      <div className="h-24 animate-pulse rounded-card bg-subtle" />
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        {Array.from({ length: 4 }, (_, i) => (
          <div key={i} className="h-20 animate-pulse rounded-control bg-subtle" />
        ))}
      </div>
    </div>
  )
}
