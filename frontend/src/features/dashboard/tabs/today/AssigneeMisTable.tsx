import type { AssigneeMisRow, DashboardFigure } from '@/api/generated/model'
import { AvatarStack } from '@/components/ui/avatar-stack'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'

import { useDrillDownStore } from '../../drillDownStore'

/**
 * Dashboard Rework Dev 1 · tab 1, PR 8 — one row per resource, ten metric
 * columns. The plan's own description: "columns sum to the cards above" and
 * "every cell a drill-down target keyed by assignee *and* metric".
 *
 * <h2>Never rendered on the OWN_WORK variant</h2>
 *
 * `resources` is empty for Developer/QA/Deployment logins — a delivery
 * role's own figures are already the seven cards, and a by-resource grid of
 * one row (themselves) answers nothing. `TodayTab` only mounts this
 * component when `resources.length > 0`, so there is no empty-grid state
 * to render here.
 *
 * <h2>Danger tokens, not a hardcoded colour</h2>
 *
 * The four "problem" columns the plan calls out — overdue start, near
 * delay, delayed, finished late — use `var(--danger-text)` once their
 * figure is greater than zero, the same token `TodaySummaryCard` uses for a
 * bad figure. A zero cell (in any column) is muted rather than styled as
 * good news, matching the prototype's own treatment.
 */
export interface AssigneeMisTableProps {
  rows: AssigneeMisRow[]
}

interface MisColumn {
  key: Exclude<keyof AssigneeMisRow, 'userId' | 'displayName'>
  label: string
  danger: boolean
}

const COLUMNS: MisColumn[] = [
  { key: 'overdueStart', label: 'Overdue start', danger: true },
  { key: 'dueToday', label: 'Due today', danger: false },
  { key: 'notStarted', label: 'Not started', danger: false },
  { key: 'wip', label: 'WIP', danger: false },
  { key: 'updatedToday', label: 'Updated', danger: false },
  { key: 'nearDelay', label: 'Near delay', danger: true },
  { key: 'delayed', label: 'Delayed', danger: true },
  { key: 'onTime', label: 'On time', danger: false },
  { key: 'finishedToday', label: 'Finished today', danger: false },
  { key: 'finishedLate', label: 'Finished late', danger: true },
]

export function AssigneeMisTable({ rows }: AssigneeMisTableProps) {
  const openPanel = useDrillDownStore((s) => s.open)

  return (
    <div
      role="group"
      aria-label="Assignee MIS"
      className="rounded-card border border-[color:var(--border)] bg-[color:var(--bg-surface)]"
    >
      <div className="flex flex-wrap items-center gap-2 p-4 pb-2">
        <span className="text-[11px] font-semibold uppercase tracking-wide text-[color:var(--text-tertiary)]">
          Assignee MIS
        </span>
        <span className="text-xs text-[color:var(--text-secondary)]">
          Per resource, as of now — red is where the conversation is
        </span>
        <span className="ml-auto text-xs text-[color:var(--text-tertiary)]">
          {rows.length} {rows.length === 1 ? 'row' : 'rows'}
        </span>
      </div>

      <div className="overflow-x-auto pb-2">
        <Table>
          <caption className="sr-only">
            Assignee MIS — ten figures per resource, each a drill-down to the tickets it counted.
          </caption>
          <TableHeader>
            <TableRow>
              <TableHead scope="col">Assignee</TableHead>
              {COLUMNS.map((column) => (
                <TableHead
                  key={column.key}
                  scope="col"
                  className="text-right"
                  style={column.danger ? { color: 'var(--danger-text)' } : undefined}
                >
                  {column.label}
                </TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((row) => (
              <TableRow key={row.userId}>
                <TableCell className="whitespace-nowrap">
                  <div className="flex items-center gap-2">
                    <AvatarStack people={[{ id: String(row.userId), name: row.displayName }]} max={1} size="sm" />
                    <span>{row.displayName}</span>
                  </div>
                </TableCell>
                {COLUMNS.map((column) => (
                  <MisCell
                    key={column.key}
                    figure={row[column.key]}
                    danger={column.danger}
                    who={row.displayName}
                    metric={column.label}
                    onOpen={openPanel}
                  />
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  )
}

type OpenDrillDown = (drillDown: string, title: string, count?: number | null) => void

function MisCell({
  figure,
  danger,
  who,
  metric,
  onOpen,
}: {
  figure: DashboardFigure | undefined
  danger: boolean
  who: string
  metric: string
  onOpen: OpenDrillDown
}) {
  const value = figure?.value ?? 0
  const color =
    danger && value > 0
      ? 'var(--danger-text)'
      : value === 0
        ? 'var(--text-tertiary)'
        : 'var(--text-primary)'

  // Bold only a real problem, never a clean zero — a bolded "0" would read as
  // an emphasised absence of the very thing the colour says not to worry about.
  const style = { color, fontWeight: danger && value > 0 ? 650 : undefined }

  if (!figure?.drillDown) {
    return (
      <TableCell className="text-right" aria-label={`${who}, ${metric}: ${value}`}>
        <span className="tabular-nums" style={style}>
          {value}
        </span>
      </TableCell>
    )
  }

  const drillDown = figure.drillDown
  return (
    <TableCell className="text-right">
      <button
        type="button"
        className="w-full tabular-nums rounded-sm transition-colors hover:bg-[color:var(--bg-subtle)]
                   focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2
                   focus-visible:outline-[color:var(--primary)]"
        style={style}
        aria-label={`${who}, ${metric}: ${value}. Open the filtered ticket list.`}
        onClick={() => onOpen(drillDown, `${who} — ${metric}`, value)}
      >
        {value}
      </button>
    </TableCell>
  )
}
