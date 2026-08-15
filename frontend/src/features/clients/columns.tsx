import { Link } from 'react-router-dom'

import { Chip } from '@/components/ui/chip'
import type { Client } from '@/api/generated/model/client'

import { clientPath } from '../tickets/detail/entityLinks'

/**
 * B-025 · the S-32 columns, in blueprint line 946's order.
 *
 * Client Code · Name · Account Manager · Support Plan · Projects · Open Tickets ·
 * Status · Last Ticket Date · Actions.
 *
 * Selection and the row-expand toggle are separate leading columns the page
 * owns, because they are controls rather than data — the same split
 * `resources/columns.tsx` makes.
 */
export interface ClientColumn {
  key: string
  header: string
  widthClassName?: string
  align?: 'left' | 'right'
  render: (client: Client) => React.ReactNode
}

/**
 * Renders a stored UTC instant in the viewer's own zone.
 *
 * PLAN.md §3.1: storage is UTC everywhere and the timezone is applied in the
 * presentation layer. This is that layer.
 *
 * **"Never" is not "—".** A client nothing has ever been raised against is a
 * real and interesting state — a new account, or one whose relationship never
 * started — and it reads differently from a date we failed to parse.
 */
export function formatLastTicketDate(value: string | null | undefined): string {
  if (!value) return 'Never'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return '—'
  return parsed.toLocaleDateString(undefined, {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  })
}

export const CLIENT_COLUMNS: ClientColumn[] = [
  {
    key: 'clientCode',
    header: 'Code',
    widthClassName: 'w-28',
    render: (client) => (
      <span className="font-mono text-caption text-content-muted">{client.clientCode}</span>
    ),
  },
  {
    key: 'name',
    header: 'Client',
    render: (client) => (
      <div className="flex flex-col">
        {/*
          The 360 view is Stream B's and not built yet; `App.tsx` registers
          `/clients/:clientId` against a named placeholder so this lands on
          "not built" rather than the catch-all Not found, which reads as a
          broken link. The path comes from `entityLinks` so the ticket detail
          page and this grid cannot drift apart.
        */}
        <Link
          to={clientPath(client.id!)}
          className="font-medium text-content hover:text-primary hover:underline"
        >
          {client.name}
        </Link>
        {client.domain ? (
          <span className="text-caption text-content-muted">{client.domain}</span>
        ) : null}
      </div>
    ),
  },
  {
    key: 'accountManager',
    header: 'Account manager',
    render: (client) =>
      client.accountManager ? (
        <span className="text-content">{client.accountManager.displayName}</span>
      ) : (
        // Nullable column, and an unassigned client is a real state rather than
        // missing data — a freshly imported one has nobody on it yet.
        <span className="text-content-muted">Unassigned</span>
      ),
  },
  {
    key: 'supportPlan',
    header: 'Support plan',
    widthClassName: 'w-32',
    render: (client) =>
      client.supportPlan ? (
        <span className="text-content">{client.supportPlan}</span>
      ) : (
        <span className="text-content-muted">—</span>
      ),
  },
  {
    key: 'projects',
    header: 'Projects',
    render: (client) => {
      const projects = client.projects ?? []
      if (projects.length === 0) return <span className="text-content-muted">None</span>
      // Two names and a count, rather than every chip: a client on nine
      // projects would otherwise own the row's whole width. The full list is a
      // title, so a hover and a screen reader both reach it.
      const shown = projects.slice(0, 2)
      return (
        <span className="flex flex-wrap items-center gap-1" title={projects.map((p) => p.name).join(', ')}>
          {shown.map((project) => (
            <Chip key={project.id} variant="neutral">
              {project.projectCode}
            </Chip>
          ))}
          {projects.length > shown.length ? (
            <span className="text-caption text-content-muted">
              +{projects.length - shown.length}
            </span>
          ) : null}
        </span>
      )
    },
  },
  {
    key: 'openTicketCount',
    header: 'Open tickets',
    widthClassName: 'w-28',
    align: 'right',
    render: (client) => (
      <span className="tabular-nums text-content">{client.openTicketCount ?? 0}</span>
    ),
  },
  {
    key: 'status',
    header: 'Status',
    widthClassName: 'w-28',
    render: (client) =>
      // Never colour alone — blueprint §12.1. The chip carries its own word, so
      // a colour-blind user and a printed page read the same row.
      client.isActive ? (
        <Chip variant="success">Active</Chip>
      ) : (
        <Chip variant="neutral">Inactive</Chip>
      ),
  },
  {
    key: 'lastTicketDate',
    header: 'Last ticket',
    widthClassName: 'w-32',
    render: (client) => (
      <span className="text-content-muted">{formatLastTicketDate(client.lastTicketDate)}</span>
    ),
  },
]
