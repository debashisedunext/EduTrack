/* eslint-disable react-refresh/only-export-components -- data module: column defs and lookup tables, no component lives here */
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { format, isPast, parseISO } from 'date-fns'
import { AlertTriangle } from 'lucide-react'
/*
 * The grid row is a TicketSummary, not a Ticket.
 *
 * GET /tickets has always returned flat ids — A-053 chose that over nested
 * objects because a list is where nesting costs most, one lookup per row across
 * fifty rows. The contract declared `Ticket` anyway, so this file was typed
 * against nested `assignee`, `project` and `client` properties the server never
 * sends: the ID column rendered blank and every row read "Unassigned" on data
 * that was entirely present.
 *
 * `Ticket` is still correct for the detail page, which does return it nested.
 */
import type { TicketSummary } from '@/api/generated/model/ticketSummary'
import type { Level } from '@/api/generated/model/level'
import type { StatusCode } from '@/api/generated/model/statusCode'
import { Chip, type ChipProps } from '@/components/ui/chip'
import { AvatarStack } from '@/components/ui/avatar-stack'

export type ColumnKey =
  | 'ticketId'
  | 'title'
  | 'taskType'
  | 'level'
  | 'assignee'
  | 'plannedCloseDate'
  | 'effort'
  | 'status'
  | 'project'
  | 'client'
  | 'reportedBy'
  | 'createdAt'

export interface ColumnRenderContext {
  /** `Ticket.taskTypeId` names a master row the list payload does not embed — resolved from `/masters/task-types`, fetched once for the filter bar and reused here. */
  taskTypeNames: Map<number, string>
  /**
   * The same pattern for the other three flat ids the list returns.
   *
   * A-053 chose flat ids over nested objects on purpose — a list is where
   * nesting costs most, one lookup per row across fifty rows — and the contract
   * has caught up with that in `TicketSummary`. What was missing was here: the
   * grid resolved `taskTypeId` through a map and expected `project`, `client`
   * and `assignee` to arrive nested, so those three columns read fields the
   * server never sends. The ID column had the same fault by another name.
   *
   * Every map is built from a list the filter bar already fetches, so this adds
   * no request.
   */
  projectNames: Map<number, string>
  clientNames: Map<number, string>
  userNames: Map<number, string>
}

export interface ColumnDef {
  key: ColumnKey
  header: string
  /** ID and Description anchor the row — hiding them would leave nothing to identify it by, so the column chooser never offers them. */
  alwaysVisible?: boolean
  align?: 'left' | 'right'
  widthClassName?: string
  render: (ticket: TicketSummary, ctx: ColumnRenderContext) => ReactNode
}

export const LEVEL_VARIANT: Record<Level, ChipProps['variant']> = {
  LOW: 'low',
  MEDIUM: 'medium',
  HIGH: 'high',
  CRITICAL: 'critical',
}

export const STATUS_VARIANT: Record<StatusCode, ChipProps['variant']> = {
  NEW: 'neutral',
  IN_PROGRESS: 'info',
  ON_HOLD: 'warning',
  AWAITING_INFO: 'warning',
  REWORK: 'warning',
  RESOLVED: 'success',
  CLOSED: 'neutral',
  REOPENED: 'danger',
}

export const STATUS_LABEL: Record<StatusCode, string> = {
  NEW: 'New',
  IN_PROGRESS: 'In Progress',
  ON_HOLD: 'On Hold',
  AWAITING_INFO: 'Awaiting Info',
  REWORK: 'Rework',
  RESOLVED: 'Resolved',
  CLOSED: 'Closed',
  REOPENED: 'Reopened',
}

export const COLUMNS: ColumnDef[] = [
  {
    key: 'ticketId',
    header: 'ID',
    alwaysVisible: true,
    widthClassName: 'whitespace-nowrap',
    // `ticketCode`, not `ticketId`. The contract's TicketId *is* the code —
    // pattern ^[A-Z][A-Z0-9]{1,9}-\d{2}-\d{5,}$ — and the list has always sent
    // it under its column name. This column was blank because it read a
    // property that never arrived.
    render: (t) => (
      <Link
        to={`/tickets/${t.ticketCode}`}
        className="font-mono font-medium text-primary tabular-nums hover:underline"
      >
        {t.ticketCode}
      </Link>
    ),
  },
  {
    key: 'title',
    header: 'Description',
    alwaysVisible: true,
    render: (t) => (
      <span className="block max-w-[28rem] truncate" title={t.title}>
        {t.title}
      </span>
    ),
  },
  {
    key: 'taskType',
    header: 'Type',
    render: (t, ctx) => (
      <Chip variant="neutral">{(t.taskTypeId != null && ctx.taskTypeNames.get(t.taskTypeId)) || '—'}</Chip>
    ),
  },
  {
    key: 'level',
    header: 'Level',
    render: (t) => <Chip variant={LEVEL_VARIANT[t.level]}>{t.level}</Chip>,
  },
  {
    key: 'assignee',
    header: 'Assignee',
    render: (t, ctx) => {
      // Null assignedTo genuinely means unassigned. Before this fix every row
      // said so, because the grid read a nested `assignee` the list does not
      // return — which looked like a data problem and was a contract one.
      const name = t.assignedTo != null ? ctx.userNames.get(t.assignedTo) : undefined;
      return t.assignedTo != null ? (
        <div className="flex items-center gap-2 whitespace-nowrap">
          <AvatarStack
            people={[{ id: String(t.assignedTo), name: name ?? `#${t.assignedTo}` }]}
            max={1}
            size="sm"
          />
          <span>{name ?? `#${t.assignedTo}`}</span>
        </div>
      ) : (
        <span className="text-content-muted">Unassigned</span>
      );
    },
  },
  {
    key: 'plannedCloseDate',
    header: 'PCD',
    widthClassName: 'whitespace-nowrap',
    render: (t) => {
      if (!t.plannedCloseDate) return <span className="text-content-muted">—</span>
      const date = parseISO(t.plannedCloseDate)
      const overdue = t.isDelayed ?? (isPast(date) && t.status !== 'CLOSED' && t.status !== 'RESOLVED')
      return (
        <span className={overdue ? 'inline-flex items-center gap-1 text-warning-text' : undefined}>
          {format(date, 'd MMM')}
          {overdue && <AlertTriangle className="h-3.5 w-3.5" aria-label="Delayed" />}
        </span>
      )
    },
  },
  {
    key: 'effort',
    header: 'Eff',
    align: 'right',
    render: (t) => <span className="tabular-nums">{(t.totalEffortHrs ?? 0).toFixed(1)}</span>,
  },
  {
    key: 'status',
    header: 'Status',
    render: (t) => <Chip variant={STATUS_VARIANT[t.status]}>{STATUS_LABEL[t.status]}</Chip>,
  },
  {
    key: 'project',
    header: 'Project',
    // The id falls back to "#4" rather than an em dash when the master list has
    // not loaded: a dash says "no project", which is never true of a ticket.
    render: (t, ctx) => ctx.projectNames.get(t.projectId) ?? `#${t.projectId}`,
  },
  {
    key: 'client',
    header: 'Client',
    // An em dash here is correct: a ticket may genuinely have no client.
    render: (t, ctx) =>
      t.clientId != null ? (
        (ctx.clientNames.get(t.clientId) ?? `#${t.clientId}`)
      ) : (
        <span className="text-content-muted">—</span>
      ),
  },
  {
    key: 'reportedBy',
    header: 'Reported by',
    render: (t, ctx) =>
      t.reportedBy != null ? (ctx.userNames.get(t.reportedBy) ?? `#${t.reportedBy}`) : '—',
  },
  {
    key: 'createdAt',
    header: 'Created',
    widthClassName: 'whitespace-nowrap',
    render: (t) => (t.createdAt ? format(parseISO(t.createdAt), 'd MMM yyyy') : '—'),
  },
]

/**
 * C-016 — row colour cue. Critical wins over delayed when both are true (in
 * practice the SLA scanner auto-promotes a delayed ticket to Critical
 * anyway, so this only matters for the gap before that scan runs, or a
 * ticket someone set Critical manually before it was ever late).
 */
export function rowCueClassName(ticket: TicketSummary): string | undefined {
  if (ticket.level === 'CRITICAL') return 'border-l-4 border-l-level-critical'
  if (ticket.isDelayed) return 'border-l-4 border-l-level-high'
  return undefined
}

export const DEFAULT_VISIBLE_COLUMNS: ColumnKey[] = [
  'ticketId',
  'title',
  'taskType',
  'level',
  'assignee',
  'plannedCloseDate',
  'effort',
  'status',
]

export const TOGGLEABLE_COLUMNS = COLUMNS.filter((c) => !c.alwaysVisible)
