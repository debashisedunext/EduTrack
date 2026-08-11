import { Link } from 'react-router-dom'
import { AlertTriangle, ChevronRight } from 'lucide-react'

import type { Ticket } from '@/api/generated/model/ticket'
import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'

import { LEVEL_VARIANT, STATUS_LABEL, STATUS_VARIANT } from '../list/columns'
import { QuickUpdateTrigger } from '../quick-update/QuickUpdatePanel'
import { delayInDays } from './ticketSummary'

/**
 * Header actions the *server* has said this caller may take.
 *
 * `availableActions` is resolved server-side precisely so the client does not
 * re-derive permissions — two implementations of the same rule always diverge,
 * and the client's copy is the one that gets it wrong. So this table maps
 * server action codes to buttons and nothing else decides what appears.
 *
 * `handoff`, `rework` and `skip-stage` also arrive in that list, but they are
 * *stage* actions: the blueprint puts them on the ribbon's current segment
 * (§4A, C-052), not in the header, so they are deliberately absent here rather
 * than duplicated in two places a user could click. `priority` is inline in
 * the summary panel (C-020). `comment`, `effort` and `attach` are the surfaces
 * below the fold, not header buttons.
 */
const HEADER_ACTIONS: { action: string; label: string; owner: string }[] = [
  { action: 'close', label: 'Close', owner: 'C-040' },
  { action: 'reopen', label: 'Reopen', owner: 'C-039' },
]

export interface TicketDetailHeaderProps {
  ticket: Ticket
  availableActions?: string[]
  now?: Date
}

export function TicketDetailHeader({ ticket, availableActions, now = new Date() }: TicketDetailHeaderProps) {
  const lateBy = delayInDays(ticket, now)
  const allowed = new Set(availableActions ?? [])

  return (
    <header className="flex flex-col gap-3 border-b border-border bg-surface px-6 py-4">
      <nav aria-label="Breadcrumb">
        <ol className="flex items-center gap-1 text-caption text-content-muted">
          <li>
            <Link to="/tickets" className="hover:text-content hover:underline">
              Tickets
            </Link>
          </li>
          <ChevronRight className="h-3 w-3" aria-hidden />
          <li aria-current="page" className="font-mono text-content">
            {ticket.ticketId}
          </li>
        </ol>
      </nav>

      <div className="flex flex-wrap items-center gap-2">
        <span className="font-mono text-sm font-medium tabular-nums text-content">{ticket.ticketId}</span>
        <Chip variant={LEVEL_VARIANT[ticket.level]}>{ticket.level}</Chip>
        <Chip variant={STATUS_VARIANT[ticket.status]}>{STATUS_LABEL[ticket.status]}</Chip>
        {lateBy != null && (
          <Chip variant="warning">
            <AlertTriangle className="h-3.5 w-3.5" aria-hidden />
            {lateBy === 0 ? 'Delayed' : `Delayed by ${lateBy} day${lateBy === 1 ? '' : 's'}`}
          </Chip>
        )}
        {(ticket.cycleNo ?? 1) > 1 && <Chip variant="danger">Cycle {ticket.cycleNo}</Chip>}
        {(ticket.iterationNo ?? 1) > 1 && <Chip variant="warning">Iteration {ticket.iterationNo}</Chip>}
      </div>

      <h1 className="text-h1 text-content">{ticket.title}</h1>

      <div className="flex flex-wrap items-center gap-2">
        <QuickUpdateTrigger ticket={ticket} />
        {HEADER_ACTIONS.filter(({ action }) => allowed.has(action)).map(({ action, label, owner }) => (
          <Button
            key={action}
            type="button"
            size="sm"
            variant="secondary"
            disabled
            // Rendered, disabled, and honest about why: the server says this
            // caller may take the action, so hiding it would misreport their
            // permissions, and enabling it would post nothing.
            title={`${label} arrives with ${owner}`}
          >
            {label}
          </Button>
        ))}
      </div>
    </header>
  )
}
