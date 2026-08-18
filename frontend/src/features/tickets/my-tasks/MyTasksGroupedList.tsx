import { Link } from 'react-router-dom'
import type { TicketSummary } from '@/api/generated/model/ticketSummary'
import { Chip } from '@/components/ui/chip'
import { rowCueClassName, LEVEL_VARIANT } from '../list/columns'
import { titleCase } from '../stageDisplay'
import { QuickUpdateTrigger } from '../quick-update/QuickUpdatePanel'
import { dueDateLabel, reworkBadge } from './taskCardHelpers'
import type { TaskGroup } from './groupTickets'
import { cn } from '@/lib/utils'

const GROUP_DOT_CLASS: Record<TaskGroup['key'], string> = {
  overdue: 'bg-level-critical',
  dueToday: 'bg-level-high',
  thisWeek: 'bg-level-medium',
  later: 'bg-content-muted',
}

export function MyTasksGroupedList({ groups }: { groups: TaskGroup[] }) {
  const nonEmpty = groups.filter((g) => g.tickets.length > 0)

  if (nonEmpty.length === 0) return null

  return (
    <div className="flex flex-col gap-6">
      {nonEmpty.map((group) => (
        <section key={group.key} aria-labelledby={`group-${group.key}`}>
          <div className="mb-2 flex items-center gap-2">
            <span className={cn('h-2 w-2 rounded-full', GROUP_DOT_CLASS[group.key])} aria-hidden />
            <h3 id={`group-${group.key}`} className="text-h3 text-content">
              {group.label}
            </h3>
            <span className="text-caption text-content-muted">{group.tickets.length}</span>
          </div>
          <div className="divide-y divide-border overflow-hidden rounded-card border border-border bg-surface">
            {group.tickets.map((ticket) => (
              <TaskRow key={ticket.ticketCode} ticket={ticket} />
            ))}
          </div>
        </section>
      ))}
    </div>
  )
}

function TaskRow({ ticket }: { ticket: TicketSummary }) {
  return (
    <div className={cn('flex items-center gap-4 px-4 py-3', rowCueClassName(ticket))}>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <Link
            to={`/tickets/${ticket.ticketCode}`}
            className="font-mono text-sm font-medium text-primary tabular-nums hover:underline"
          >
            {ticket.ticketCode}
          </Link>
          <Chip variant={LEVEL_VARIANT[ticket.level]}>{ticket.level}</Chip>
          {reworkBadge(ticket)}
        </div>
        <p className="mt-1 truncate text-sm text-content" title={ticket.title}>
          {ticket.title}
        </p>
        <p className="mt-1 truncate text-caption text-content-muted">
          {ticket.currentStage ? `In ${titleCase(ticket.currentStage)} · ` : ''}
          due {dueDateLabel(ticket)} · {(ticket.totalEffortHrs ?? 0).toFixed(1)}h logged
        </p>
      </div>
      <QuickUpdateTrigger
        ticket={{
          // The list row's names, mapped to what the panel asks for.
          ticketId: ticket.ticketCode,
          status: ticket.status,
          title: ticket.title,
          currentStageCode: ticket.currentStage,
          iterationNo: ticket.iterationNo,
        }}
      />
    </div>
  )
}
