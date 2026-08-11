import { differenceInCalendarDays, parseISO } from 'date-fns'
import type { Ticket } from '@/api/generated/model/ticket'

export type TaskGroupKey = 'overdue' | 'dueToday' | 'thisWeek' | 'later'

export interface TaskGroup {
  key: TaskGroupKey
  label: string
  tickets: Ticket[]
}

export const GROUP_LABELS: Record<TaskGroupKey, string> = {
  overdue: 'Overdue',
  dueToday: 'Due Today',
  thisWeek: 'This Week',
  later: 'Later',
}

// Most-urgent-first — the prototype's own ordering (docs/prototype/index.html,
// the `GRP` array behind S-18) rather than the sequence the blueprint's prose
// happens to list them in.
const GROUP_ORDER: TaskGroupKey[] = ['overdue', 'dueToday', 'thisWeek', 'later']

/**
 * Buckets by `plannedCloseDate` against the viewer's local calendar day —
 * calendar-day granularity, not an exact-timestamp comparison. A ticket due
 * at 14:00 today belongs in "Due Today" all day, not "Overdue" from 14:01.
 * `columns.tsx`'s `rowCueClassName` and PCD-column ⚠ deliberately use the
 * precise `isPast()` comparison instead — that one is answering "has the SLA
 * clock breached", a different question from "which day should this be
 * worked". A ticket whose ETA was just revised (Quick Update's `revisedEta`
 * overwrites `plannedCloseDate` server-side) reshuffles groups immediately
 * because both read the same field.
 *
 * A ticket with no `plannedCloseDate` at all — should not happen once C-012's
 * SLA computation has run, but a ticket created before that landed, or one
 * whose PCD failed to compute — falls into Later rather than being dropped.
 */
export function groupTickets(tickets: Ticket[], today: Date = new Date()): TaskGroup[] {
  const buckets: Record<TaskGroupKey, Ticket[]> = { overdue: [], dueToday: [], thisWeek: [], later: [] }
  for (const ticket of tickets) {
    buckets[bucketFor(ticket, today)].push(ticket)
  }
  return GROUP_ORDER.map((key) => ({ key, label: GROUP_LABELS[key], tickets: buckets[key] }))
}

function bucketFor(ticket: Ticket, today: Date): TaskGroupKey {
  if (!ticket.plannedCloseDate) return 'later'
  const daysUntilDue = differenceInCalendarDays(parseISO(ticket.plannedCloseDate), today)
  if (daysUntilDue < 0) return 'overdue'
  if (daysUntilDue === 0) return 'dueToday'
  if (daysUntilDue <= 7) return 'thisWeek'
  return 'later'
}
