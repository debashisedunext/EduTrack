import * as React from 'react'
import { Link } from 'react-router-dom'
import { AlertTriangle } from 'lucide-react'
import { newIdempotencyKey, ApiError } from '@/api/http'
import type { TicketSummary } from '@/api/generated/model/ticketSummary'
import type { Level } from '@/api/generated/model/level'
import { StatusCode } from '@/api/generated/model/statusCode'
import { Chip } from '@/components/ui/chip'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { toast } from '@/components/ui/use-toast'
import { cn } from '@/lib/utils'
import { rowCueClassName, LEVEL_VARIANT, STATUS_LABEL, STATUS_VARIANT } from '../list/columns'
import { useQuickUpdateMutation } from '../quick-update/useQuickUpdateMutation'
import { QuickUpdateTrigger } from '../quick-update/QuickUpdatePanel'
import { dueDateLabel, isOverdue, reworkBadge } from './taskCardHelpers'

// CLOSED is not a My Tasks status — the page already fetches with
// `excludeClosed: true`, so a Closed column would only ever be empty.
const BOARD_STATUSES: StatusCode[] = [
  StatusCode.NEW,
  StatusCode.REOPENED,
  StatusCode.IN_PROGRESS,
  StatusCode.REWORK,
  StatusCode.ON_HOLD,
  StatusCode.AWAITING_INFO,
  StatusCode.RESOLVED,
]

/**
 * S-18's "optional Kanban toggle (drag between statuses)".
 *
 * Native HTML5 drag-and-drop is mouse/touch-only — it has no keyboard
 * equivalent a screen reader user or a keyboard-only user can reach, and
 * CLAUDE.md's accessibility rule is not optional. Every card's "Move to"
 * select performs the identical status change and is the one every user can
 * reach; drag is a faster path layered on top for a mouse, not the only path.
 */
export function MyTasksKanbanBoard({ tickets }: { tickets: TicketSummary[] }) {
  const quickUpdate = useQuickUpdateMutation()
  const [dragOverStatus, setDragOverStatus] = React.useState<StatusCode | null>(null)
  const [movingTicketId, setMovingTicketId] = React.useState<string | null>(null)

  const byStatus = React.useMemo(() => {
    const map = new Map<StatusCode, TicketSummary[]>(BOARD_STATUSES.map((s) => [s, []]))
    for (const ticket of tickets) {
      map.get(ticket.status)?.push(ticket)
    }
    return map
  }, [tickets])

  async function moveTo(ticket: TicketSummary, status: StatusCode) {
    if (status === ticket.status) return
    setMovingTicketId(ticket.ticketCode)
    try {
      await quickUpdate.mutateAsync({
        ticketId: ticket.ticketCode,
        data: { status },
        idempotencyKey: newIdempotencyKey(),
      })
      toast({ variant: 'success', title: `${ticket.ticketCode} moved to ${STATUS_LABEL[status]}` })
    } catch (error) {
      toast({
        variant: 'danger',
        title: `Could not move ${ticket.ticketCode}`,
        description: error instanceof ApiError ? error.problem.detail ?? error.message : 'Try again in a moment.',
      })
    } finally {
      setMovingTicketId(null)
    }
  }

  return (
    <div className="flex h-full gap-4 overflow-x-auto pb-2">
      {BOARD_STATUSES.map((status) => {
        const columnTickets = byStatus.get(status) ?? []
        return (
          <div
            key={status}
            role="group"
            aria-label={STATUS_LABEL[status]}
            onDragOver={(e) => {
              e.preventDefault()
              setDragOverStatus(status)
            }}
            onDragLeave={() => setDragOverStatus((current) => (current === status ? null : current))}
            onDrop={(e) => {
              e.preventDefault()
              setDragOverStatus(null)
              const ticketId = e.dataTransfer.getData('text/plain')
              const ticket = tickets.find((t) => t.ticketCode === ticketId)
              if (ticket) void moveTo(ticket, status)
            }}
            className={cn(
              'flex w-72 shrink-0 flex-col rounded-card border border-border bg-subtle/40 p-2',
              dragOverStatus === status && 'ring-2 ring-primary',
            )}
          >
            <div className="flex items-center gap-2 px-1.5 py-1">
              <Chip variant={STATUS_VARIANT[status]}>{STATUS_LABEL[status]}</Chip>
              <span className="text-caption text-content-muted">{columnTickets.length}</span>
            </div>
            <div className="flex flex-1 flex-col gap-2 overflow-y-auto p-1">
              {columnTickets.map((ticket) => (
                <KanbanCard
                  key={ticket.ticketCode}
                  ticket={ticket}
                  moving={movingTicketId === ticket.ticketCode}
                  onMoveTo={(next) => void moveTo(ticket, next)}
                />
              ))}
            </div>
          </div>
        )
      })}
    </div>
  )
}

function KanbanCard({
  ticket,
  moving,
  onMoveTo,
}: {
  ticket: TicketSummary
  moving: boolean
  onMoveTo: (status: StatusCode) => void
}) {
  return (
    <div
      draggable
      onDragStart={(e) => {
        e.dataTransfer.setData('text/plain', ticket.ticketCode)
        e.dataTransfer.effectAllowed = 'move'
      }}
      aria-busy={moving || undefined}
      className={cn(
        'flex flex-col gap-2 rounded-control border border-border bg-surface p-3 shadow-rest',
        rowCueClassName(ticket),
        moving && 'opacity-60',
      )}
    >
      <div className="flex flex-wrap items-center gap-1.5">
        <Link
          to={`/tickets/${ticket.ticketCode}`}
          className="font-mono text-caption font-medium text-primary tabular-nums hover:underline"
        >
          {ticket.ticketCode}
        </Link>
        <Chip variant={LEVEL_VARIANT[ticket.level as Level]}>{ticket.level}</Chip>
        {reworkBadge(ticket)}
      </div>
      <p className="line-clamp-2 text-sm text-content" title={ticket.title}>
        {ticket.title}
      </p>
      <p className="flex items-center gap-1 text-caption text-content-muted">
        {isOverdue(ticket) && <AlertTriangle className="h-3.5 w-3.5 text-warning-text" aria-label="Overdue" />}
        due {dueDateLabel(ticket)}
      </p>
      <div className="flex items-center gap-2">
        <Select value={ticket.status} onValueChange={(value) => onMoveTo(value as StatusCode)}>
          <SelectTrigger aria-label={`Move ${ticket.ticketCode} to a different status`} className="h-8 flex-1 text-caption">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {BOARD_STATUSES.map((status) => (
              <SelectItem key={status} value={status}>
                {STATUS_LABEL[status]}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <QuickUpdateTrigger
          ticket={{
            ticketId: ticket.ticketCode,
            title: ticket.title,
            status: ticket.status,
            currentStageCode: ticket.currentStage,
            iterationNo: ticket.iterationNo,
          }}
          triggerClassName="h-8 px-2"
          compact
        />
      </div>
    </div>
  )
}
