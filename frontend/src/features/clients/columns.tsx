import { Link } from 'react-router-dom'

import { Chip } from '@/components/ui/chip'
import type { Client } from '@/api/generated/model/client'

import { clientPath } from '../tickets/detail/entityLinks'
import { SUPPORT_PLANS } from './clientForm'

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

/**
 * The code as a human reads it.
 *
 * Falls back to the stored string rather than to a dash: a plan this build does
 * not know about is still something the organisation wrote down, and blanking it
 * would hide a row that needs correcting.
 */
export function supportPlanLabel(code: string): string {
  return SUPPORT_PLANS.find((plan) => plan.value === code.toUpperCase())?.label ?? code
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
    // B-026 · the stored value is an upper-case code — `PREMIUM`, which is what
    // `ReferenceDataFixture` and every server write have always held. The grid
    // showed it raw, which was invisible only because the MSW mock was storing
    // title-case and disagreeing with the server. Labelled here rather than
    // stored differently: the code is the vocabulary, the label is presentation.
    render: (client) =>
      client.supportPlan ? (
        <span className="text-content">{supportPlanLabel(client.supportPlan)}</span>
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
    // B-026 · reads `status`, not `isActive`, and that is the point of putting
    // the field on the list row at all. §4B.2's Identity group has three states
    // and `isActive` collapses two of them: a Prospect is active by that
    // projection — deliberately, so it stays in the ticket form's client
    // dropdown — so a boolean chip would label a prospect and a contracted
    // client identically. `isActive` remains the fallback for a row served by a
    // backend that predates the field.
    //
    // Never colour alone (§12.1). Each chip carries its own word, so a
    // colour-blind user and a printed page read the same row.
    render: (client) => {
      const status = client.status ?? (client.isActive ? 'ACTIVE' : 'INACTIVE')
      if (status === 'PROSPECT') return <Chip variant="info">Prospect</Chip>
      if (status === 'INACTIVE') return <Chip variant="neutral">Inactive</Chip>
      return <Chip variant="success">Active</Chip>
    },
  },
  {
    key: 'lastTicketDate',
    header: 'Last ticket',
    widthClassName: 'w-32',
    render: (client) => (
      <span className="text-content-muted">{formatLastTicketDate(client.lastTicketDate)}</span>
    ),
  },
  {
    key: 'actions',
    header: 'Actions',
    widthClassName: 'w-20',
    // B-026 · blueprint line 946's ninth column, which had nothing to point at
    // until S-33 existed. A link and not a menu: there is exactly one action, and
    // a kebab hiding a single item is a click bought for nothing.
    // The accessible name is `aria-label`, not an `sr-only` span carrying the
    // client's name. A span would put a second copy of that name in the row's
    // text, so `getByText('Acme Retail Ltd')` — and any screen reader reading
    // the row straight through — would meet it twice.
    render: (client) => (
      <Link
        to={`/masters/clients/${client.id}/edit`}
        aria-label={`Edit ${client.name}`}
        className="text-sm text-primary hover:underline"
      >
        Edit
      </Link>
    ),
  },
]
