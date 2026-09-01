import * as React from 'react'
import { Link } from 'react-router-dom'
import { ChevronDown } from 'lucide-react'

import { Chip } from '@/components/ui/chip'
import { LEVEL_VARIANT } from '@/features/tickets/list/columns'

import { groupTickets, type GroupableTicket, type TicketGroup } from '../../groupTickets'

/**
 * S-05 tab 3, PR 13b — one section's tickets, nested Client → Module →
 * Severity with a roll-up count on every header.
 *
 * <h2>The count is the true count, never the rendered one</h2>
 *
 * `groupTickets` returns both and the distinction is the whole reason it is a
 * tested module: past the 200-row cap they diverge, and a header showing the
 * number of rows it happened to draw is the version nobody reports as a bug —
 * they just stop trusting the screen. Every header here prints `count`, and
 * the truncation notice explains the gap rather than hiding it.
 *
 * <h2>Headers are not drill-down targets in this PR</h2>
 *
 * The plan wants them to be. They are not yet, and that is deliberate rather
 * than forgotten: a header's drill-down would have to be built here, in the
 * browser, from `clientId`/`moduleId`/`level` plus the section's own filter —
 * and the dashboard's standing rule, stated in `groupTickets` itself and in
 * `drillDownParams`, is that a drill-down is built once, server-side, so the
 * filter that produced a figure and the filter that opens it cannot drift.
 * Minting them here would be the third opinion about drill-downs that rule
 * exists to prevent, and the group counts come from a client-side grouping of
 * a capped list rather than from a summary table, so a header claiming 40
 * could open a list of 200. The rows themselves link to their tickets, and
 * "View all" opens the whole section. Raised in the PR rather than solved
 * quietly.
 */

/**
 * What this component needs of a ticket row, shaped so the generated
 * {@code TicketSummary} satisfies it structurally with no cast — the fields
 * below are its own, narrowed to the handful drawn here. Widening this to
 * "whatever the list returns" would let a rename pass typecheck and render
 * blank cells.
 */
export interface WeeklyTicketRow extends GroupableTicket {
  title?: string
  assignee?: { displayName?: string } | null
  plannedCloseDate?: string | null
}

export interface GroupedTicketAccordionProps {
  tickets: readonly WeeklyTicketRow[]
  /** From the priorities master, worst first. Unknown levels sort last. */
  severityOrder?: readonly string[]
  moduleLabel?: (moduleId: number) => string | null | undefined
}

export function GroupedTicketAccordion({
  tickets,
  severityOrder,
  moduleLabel,
}: GroupedTicketAccordionProps) {
  const grouped = React.useMemo(
    () => groupTickets(tickets, (row) => row, { severityOrder, moduleLabel }),
    [tickets, severityOrder, moduleLabel],
  )

  if (grouped.total === 0) {
    return null
  }

  return (
    <div className="flex flex-col gap-1 p-2">
      {grouped.groups.map((group) => (
        <GroupNode key={group.key} node={group} depth={0} />
      ))}

      {grouped.truncated > 0 && (
        <p className="px-2 py-1 text-xs text-[color:var(--text-secondary)]">
          Showing {grouped.rendered} of {grouped.total} tickets. The counts above are the true
          totals.
        </p>
      )}
    </div>
  )
}

/**
 * Client and module levels start open, severity levels start closed.
 *
 * Three collapsed levels would make the reader click three times to see a
 * single ticket, and three open ones would defeat the grouping — the severity
 * header is where the useful summary sits, so that is the level that stays
 * folded.
 */
function GroupNode<T extends WeeklyTicketRow>({
  node,
  depth,
}: {
  node: TicketGroup<T>
  depth: number
}) {
  const isLeaf = node.children.length === 0
  const [open, setOpen] = React.useState(depth < 2)
  const panelId = React.useId()

  return (
    <div className={depth > 0 ? 'ml-3 border-l border-[color:var(--border)] pl-2' : undefined}>
      <button
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((was) => !was)}
        className="flex w-full items-center gap-2 rounded-control px-2 py-1 text-left transition-colors hover:bg-[color:var(--bg-subtle)] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--primary)]"
      >
        <ChevronDown
          className={`h-3.5 w-3.5 shrink-0 text-[color:var(--text-secondary)] transition-transform ${
            open ? '' : '-rotate-90'
          }`}
          aria-hidden="true"
        />
        <span
          className={
            depth === 0
              ? 'text-sm font-semibold text-[color:var(--text-primary)]'
              : 'text-xs text-[color:var(--text-secondary)]'
          }
        >
          {node.label}
        </span>
        {/* The true count, not the rendered one — see the class note. */}
        <span className="ml-auto rounded-chip bg-subtle px-2 py-0.5 text-xs font-semibold tabular-nums text-content-muted">
          {node.count}
        </span>
      </button>

      {open && (
        <div id={panelId}>
          {isLeaf ? (
            <ul className="flex flex-col">
              {node.rows.map((row) => (
                <TicketLine key={row.ticketId} row={row} />
              ))}
              {node.truncated > 0 && (
                <li className="px-2 py-1 pl-7 text-xs text-[color:var(--text-secondary)]">
                  …and {node.truncated} more not shown
                </li>
              )}
            </ul>
          ) : (
            node.children.map((child) => (
              <GroupNode key={child.key} node={child} depth={depth + 1} />
            ))
          )}
        </div>
      )}
    </div>
  )
}

function TicketLine({ row }: { row: WeeklyTicketRow }) {
  return (
    <li>
      <Link
        to={`/tickets/${row.ticketId}`}
        className="flex items-center gap-2 rounded-control px-2 py-1 pl-7 text-xs transition-colors hover:bg-[color:var(--bg-subtle)] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--primary)]"
      >
        <span className="shrink-0 font-mono text-[color:var(--text-secondary)]">
          {row.ticketId}
        </span>
        <span className="truncate text-[color:var(--text-primary)]" title={row.title ?? undefined}>
          {row.title}
        </span>
        {row.assignee?.displayName && (
          <span className="ml-auto shrink-0 text-[color:var(--text-secondary)]">
            {row.assignee.displayName}
          </span>
        )}
        <Chip variant={LEVEL_VARIANT[row.level] ?? 'neutral'} className="shrink-0">
          {row.level}
        </Chip>
      </Link>
    </li>
  )
}
