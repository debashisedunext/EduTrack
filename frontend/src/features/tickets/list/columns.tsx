/* eslint-disable react-refresh/only-export-components -- data module: column defs and lookup tables, no component lives here */
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { format, isPast, parseISO } from 'date-fns'
import { AlertTriangle } from 'lucide-react'
import type { Ticket } from '@/api/generated/model/ticket'
import type { Level } from '@/api/generated/model/level'
import type { StatusCode } from '@/api/generated/model/statusCode'
import { Chip, type ChipProps } from '@/components/ui/chip'
import { AvatarStack } from '@/components/ui/avatar-stack'

export type ColumnKey =
  | 'ticketId'
  | 'title'
  | 'taskType'
  | 'module'
  | 'level'
  | 'assignee'
  | 'plannedCloseDate'
  | 'effort'
  | 'status'
  | 'project'
  | 'client'
  | 'reportedBy'
  | 'createdAt'
  | 'actualCloseDate'

export interface ColumnRenderContext {
  /** `Ticket.taskTypeId` names a master row the list payload does not embed — resolved from `/masters/task-types`, fetched once for the filter bar and reused here. */
  taskTypeNames: Map<number, string>
  /**
   * C-070 · same arrangement for `moduleId`, and **built from every row the
   * master returns, retired ones included**. `GET /masters/modules` includes
   * deactivated rows for exactly this reason (D-060): a grid still has to render
   * the name of a module some old ticket was raised against, and resolving only
   * the active ones would blank that cell — which reads as missing data rather
   * than as a retirement.
   */
  moduleNames: Map<number, string>
}

export interface ColumnDef {
  key: ColumnKey
  header: string
  /** ID and Description anchor the row — hiding them would leave nothing to identify it by, so the column chooser never offers them. */
  alwaysVisible?: boolean
  align?: 'left' | 'right'
  widthClassName?: string
  render: (ticket: Ticket, ctx: ColumnRenderContext) => ReactNode
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
    render: (t) => (
      <Link
        to={`/tickets/${t.ticketId}`}
        className="font-mono font-medium text-primary tabular-nums hover:underline"
      >
        {t.ticketId}
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
    // C-070 · off by default — see DEFAULT_VISIBLE_COLUMNS. Blueprint line 986:
    // "an optional Module column — off by default in the column chooser,
    // because the grid is already at its width budget, but filterable always."
    key: 'module',
    header: 'Module',
    render: (t, ctx) => {
      const name = t.moduleId != null ? ctx.moduleNames.get(t.moduleId) : undefined
      return name ?? <span className="text-content-muted">—</span>
    },
  },
  {
    key: 'level',
    header: 'Level',
    render: (t) => <Chip variant={LEVEL_VARIANT[t.level]}>{t.level}</Chip>,
  },
  {
    key: 'assignee',
    header: 'Assignee',
    render: (t) =>
      t.assignee ? (
        <div className="flex items-center gap-2 whitespace-nowrap">
          <AvatarStack people={[{ id: String(t.assignee.id), name: t.assignee.displayName }]} max={1} size="sm" />
          <span>{t.assignee.displayName}</span>
        </div>
      ) : (
        <span className="text-content-muted">Unassigned</span>
      ),
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
    render: (t) => (t.project ? `${t.project.projectCode} — ${t.project.name}` : '—'),
  },
  {
    key: 'client',
    header: 'Client',
    render: (t) => (t.client?.name ? t.client.name : <span className="text-content-muted">—</span>),
  },
  {
    key: 'reportedBy',
    header: 'Reported by',
    render: (t) => t.reportedBy?.displayName ?? '—',
  },
  {
    key: 'createdAt',
    header: 'Created',
    widthClassName: 'whitespace-nowrap',
    render: (t) => (t.createdAt ? format(parseISO(t.createdAt), 'd MMM yyyy') : '—'),
  },
  {
    // D-063. The fourth of the four dates asked for on 18 Aug; `createdAt` and
    // `plannedCloseDate` above are two of them, and the "actual start" that was
    // the third turned out to be the cycle's start — already modelled on
    // `ticket_cycles`, and not a ticket-level field at all.
    //
    // A blank here on a reopened ticket is CORRECT and should not be "fixed":
    // reopen nulls the ticket's actual close date because the ticket is open
    // again, and cycle 1's close is preserved on its cycle, where C-053's
    // selector reads it. The row shows the ticket's state; the cycle view shows
    // each cycle's.
    key: 'actualCloseDate',
    header: 'Closed',
    widthClassName: 'whitespace-nowrap',
    render: (t) =>
      t.actualCloseDate ? (
        format(parseISO(t.actualCloseDate), 'd MMM yyyy')
      ) : (
        <span className="text-content-muted">—</span>
      ),
  },
]

/**
 * C-016 — row colour cue. Critical wins over delayed when both are true (in
 * practice the SLA scanner auto-promotes a delayed ticket to Critical
 * anyway, so this only matters for the gap before that scan runs, or a
 * ticket someone set Critical manually before it was ever late).
 */
export function rowCueClassName(ticket: Ticket): string | undefined {
  if (ticket.level === 'CRITICAL') return 'border-l-4 border-l-level-critical'
  if (ticket.isDelayed) return 'border-l-4 border-l-level-high'
  return undefined
}

/**
 * D-063 · `createdAt` and `actualCloseDate` join the default set.
 *
 * Three of the four dates asked for on 18 Aug are now visible without opening
 * the chooser — created, planned close, actual close — because a column nobody
 * can find is not a column that was added. The fourth, "actual start", is the
 * cycle's start and is not a ticket-level field.
 *
 * This does spend width, which §S-17 notes is already tight (it is why Module
 * is chooser-only). Both remain switchable, and anyone who has already opened
 * the list keeps their own saved selection — `useListPreferences` persists
 * `visibleColumns`, so this changes the default for a *new* reader, not an
 * existing one's layout.
 */
export const DEFAULT_VISIBLE_COLUMNS: ColumnKey[] = [
  'ticketId',
  'title',
  'taskType',
  'level',
  'assignee',
  'createdAt',
  'plannedCloseDate',
  'actualCloseDate',
  'effort',
  'status',
]

export const TOGGLEABLE_COLUMNS = COLUMNS.filter((c) => !c.alwaysVisible)
