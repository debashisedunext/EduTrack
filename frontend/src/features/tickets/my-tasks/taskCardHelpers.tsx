import { format, isPast, parseISO } from 'date-fns'
import type { Ticket } from '@/api/generated/model/ticket'
import { Chip } from '@/components/ui/chip'

/** `d MMM` for a due date, or an em dash if the ticket somehow has none. */
export function dueDateLabel(ticket: Ticket): string {
  if (!ticket.plannedCloseDate) return '—'
  return format(parseISO(ticket.plannedCloseDate), 'd MMM')
}

export function isOverdue(ticket: Ticket): boolean {
  if (!ticket.plannedCloseDate) return false
  return (
    ticket.isDelayed ?? (isPast(parseISO(ticket.plannedCloseDate)) && ticket.status !== 'CLOSED' && ticket.status !== 'RESOLVED')
  )
}

/**
 * The prototype's "↺ back from QA" badge, mapped onto fields the contract
 * actually has. `REOPENED` is the status the API already sets when a closed
 * ticket comes back (blueprint §7's reopen flow); `iterationNo > 1` is a
 * backward move *within* the current cycle — a QA fail bouncing it back to
 * Development — same amber "reworked" convention the ribbon dot uses.
 */
export function reworkBadge(ticket: Ticket) {
  if (ticket.status === 'REOPENED') {
    return (
      <Chip variant="danger">
        <span aria-hidden>↺</span> Reopened
      </Chip>
    )
  }
  if (ticket.iterationNo && ticket.iterationNo > 1) {
    return (
      <Chip variant="warning">
        <span aria-hidden>↺</span> Iteration {ticket.iterationNo}
      </Chip>
    )
  }
  return null
}
