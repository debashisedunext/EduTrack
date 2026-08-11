import { differenceInCalendarDays, parseISO } from 'date-fns'
import type { Cycle } from '@/api/generated/model/cycle'
import type { HistoryEntry } from '@/api/generated/model/historyEntry'
import type { Ticket } from '@/api/generated/model/ticket'
import type { UserRef } from '@/api/generated/model/userRef'

/**
 * The arithmetic behind the summary panel, kept out of the component so it can
 * be tested without a DOM and without a fixed clock. Every function that needs
 * "now" takes it as an argument — a helper that reads `new Date()` internally
 * is a test that passes today and fails on the day the fixture dates go stale.
 */

/** Whole days since the ticket was raised. `null` when the API omits `createdAt`. */
export function ageInDays(ticket: Ticket, now: Date): number | null {
  if (!ticket.createdAt) return null
  return Math.max(0, differenceInCalendarDays(now, parseISO(ticket.createdAt)))
}

/**
 * How late the ticket is, in whole days, or `null` if it is not late.
 *
 * `isDelayed` is the server's verdict and is the only thing that decides
 * *whether* the badge shows — recomputing it here from the planned close date
 * would be a second implementation of a rule the SLA scanner already owns
 * against the working calendar, and the two would disagree over a weekend.
 * `delayedSince` is when the breach happened; falling back to the planned
 * close date covers a ticket flagged before D's scanner stamped the timestamp.
 */
export function delayInDays(ticket: Ticket, now: Date): number | null {
  if (!ticket.isDelayed) return null
  const since = ticket.delayedSince ?? ticket.plannedCloseDate
  if (!since) return null
  return Math.max(0, differenceInCalendarDays(now, parseISO(since)))
}

/**
 * Logged hours against the estimate, as a signed percentage.
 *
 * `null` when there is no estimate to compare against — a ticket nobody
 * estimated is not "0% over", and rendering it as such invents a number the
 * team never agreed to. A zero estimate is treated the same way: dividing by
 * it produces `Infinity`, which formats as "+∞%" and tells nobody anything.
 */
export function effortVariancePct(estimatedHrs: number | null | undefined, loggedHrs: number): number | null {
  if (estimatedHrs == null || estimatedHrs <= 0) return null
  return Math.round(((loggedHrs - estimatedHrs) / estimatedHrs) * 100)
}

/**
 * Total effort across **every** cycle.
 *
 * Summed from `cycles[]` rather than read off `ticket.totalEffortHrs` because
 * the detail payload is cycle-scoped: `effortLogs` holds only the selected
 * cycle's rows, so summing those would make the grand total shrink the moment
 * a reader selected cycle 1. `cycles[].totalEffortHrs` is per-cycle and
 * complete regardless of which cycle is being viewed.
 *
 * Falls back to `ticket.totalEffortHrs` when `cycles` is absent, which is
 * legal — every field of `TicketDetailResponse.data` except `ticket` is
 * optional in the contract.
 */
export function totalEffortHrs(ticket: Ticket, cycles: Cycle[] | undefined): number {
  if (!cycles?.length) return ticket.totalEffortHrs ?? 0
  const sum = cycles.reduce((total, cycle) => total + (cycle.totalEffortHrs ?? 0), 0)
  return Math.round(sum * 10) / 10
}

/** Effort logged inside one cycle, for the per-cycle links in the panel. */
export function cycleEffortHrs(cycles: Cycle[] | undefined, cycleNo: number): number {
  return cycles?.find((c) => c.cycleNo === cycleNo)?.totalEffortHrs ?? 0
}

/**
 * Who assigned this ticket to its current owner.
 *
 * ⚠ The contract has no `assignedBy` field, so this is derived: the most
 * recent history entry that changed `assigneeId` names the actor who did it.
 * That is correct for every reassignment, and `null` for a ticket still with
 * its original assignee — the `CREATED` row does not record an assignment
 * separately from the creation. The panel renders "—" in that case rather than
 * guessing at `reportedBy`, which is a different person for anything the
 * support desk raises on a client's behalf.
 *
 * `history` is cycle-scoped by the endpoint, so a reassignment in cycle 1 is
 * invisible while cycle 2 is selected. That is the right answer for a panel
 * describing the selected cycle, and it is why this reads the payload rather
 * than firing a second unscoped `GET /history`.
 */
export function assignedBy(history: HistoryEntry[] | undefined): UserRef | null {
  if (!history?.length) return null
  for (let i = history.length - 1; i >= 0; i -= 1) {
    const entry = history[i]
    if (entry.fieldName === 'assigneeId' && entry.actor) return entry.actor
  }
  return null
}

/** `14.5 h`, or `—` when there is nothing to show. Never a bare `0` for absent data. */
export function hoursLabel(hours: number | null | undefined): string {
  if (hours == null) return '—'
  return `${hours.toFixed(1)} h`
}
