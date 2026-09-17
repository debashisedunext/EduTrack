import { useNavigate } from 'react-router-dom'
import { format, parseISO } from 'date-fns'

import type { ObProjectBoardRow } from '@/api/generated/model'
import { Chip } from '@/components/ui/chip'

import { byLatenessDescending, bucketLook, lateLabel } from './obProjectBoard'

/**
 * OB-02's Summary tab — today's deliveries, the at-risk clients and the
 * overdue ones, each naming the implementor.
 *
 * <h2>Three lists rather than one filtered grid</h2>
 *
 * They answer three different questions at three different urgencies: what
 * lands today, what has gone past rescuing, and what is slipping. A single
 * table with a status column would make a reader do the sorting that the
 * screen exists to have already done.
 *
 * <h2>Every list is cut from the rows the cards were counted from</h2>
 *
 * The props are the board's own `projects` array, so "At risk: 4" on the card
 * band and four rows in this column are the same four projects by
 * construction rather than by two queries agreeing. That is the whole reason
 * the board is one request.
 */
export interface ObProjectSummaryListsProps {
  rows: ObProjectBoardRow[]
  /** The board's `today`, in the working calendar's timezone — never the browser's. */
  today: string
}

export function ObProjectSummaryLists({ rows, today }: ObProjectSummaryListsProps) {
  const todays = rows.filter((row) => row.tentativeCompletion === today)
  const atRisk = rows.filter((row) => row.bucket === 'AT_RISK').sort(byLatenessDescending)
  const overdue = rows.filter((row) => row.bucket === 'DELAYED').sort(byLatenessDescending)

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
      <ListCard
        title="Today's delivery"
        colour="var(--chart-2)"
        rows={todays}
        empty="Nothing is due to complete today."
        detail={(row) => row.currentStage ?? 'no stage running'}
      />
      <ListCard
        title="At-risk clients"
        colour={bucketLook('AT_RISK').colour}
        rows={atRisk}
        empty="No project is more than 7 working days past its date."
        detail={(row) => `due ${formatDate(row.tentativeCompletion)}`}
      />
      <ListCard
        title="Overdue clients"
        colour={bucketLook('DELAYED').colour}
        rows={overdue}
        empty="Nothing is overdue. Keep it that way."
        detail={(row) => `due ${formatDate(row.tentativeCompletion)}`}
      />
    </div>
  )
}

function ListCard({
  title,
  colour,
  rows,
  empty,
  detail,
}: {
  title: string
  colour: string
  rows: ObProjectBoardRow[]
  empty: string
  detail: (row: ObProjectBoardRow) => string
}) {
  return (
    <section
      aria-label={title}
      className="overflow-hidden rounded-card border border-border bg-surface shadow-rest"
    >
      <div className="flex items-center gap-2 border-b border-border px-4 py-2.5">
        <span
          aria-hidden="true"
          className="h-2.5 w-2.5 rounded-full"
          style={{ backgroundColor: colour }}
        />
        <h3 className="flex-1 text-sm font-semibold text-content">{title}</h3>
        <span className="text-xs tabular-nums text-content-muted">{rows.length}</span>
      </div>

      {rows.length === 0 ? (
        <p className="px-4 py-6 text-center text-xs text-content-muted">{empty}</p>
      ) : (
        <ul>
          {rows.map((row, index) => (
            <li key={row.id} className={index > 0 ? 'border-t border-border' : ''}>
              <Row row={row} detail={detail(row)} />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

/**
 * One project.
 *
 * A button rather than a link for the reason the card tiles are: the
 * destination is a route, but the row carries a whole line of secondary text
 * and an anchor wrapping it would be announced as one long link name. The
 * navigation is the same either way; `useNavigate` keeps the accessible name
 * to the client's name and the action.
 */
function Row({ row, detail }: { row: ObProjectBoardRow; detail: string }) {
  const navigate = useNavigate()
  const late = lateLabel(row)
  const look = bucketLook(row.bucket)

  return (
    <button
      type="button"
      onClick={() => navigate(`/onboarding/projects/${row.id}`)}
      aria-label={`${row.client.name}, ${row.product.name}. ${
        row.implementor ? `Implementor ${row.implementor.displayName}.` : 'No implementor assigned.'
      } ${late ?? look.label}. Open the project.`}
      className="flex w-full items-start gap-3 px-4 py-3 text-left hover:bg-subtle
                 focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2
                 focus-visible:outline-primary"
    >
      <span className="min-w-0 flex-1">
        <span className="block truncate text-sm font-medium text-content">{row.client.name}</span>
        <span className="mt-0.5 block truncate text-xs text-content-muted">
          {/*
            The implementor is named on every row of every list, which is what
            was asked for: a list of late clients without the person delivering
            them tells you what is wrong and not who to talk to.
          */}
          {row.implementor ? row.implementor.displayName : 'Unassigned'} · {row.product.name} ·{' '}
          {detail}
        </span>
      </span>
      <span className="flex shrink-0 flex-col items-end gap-1">
        <Chip variant={look.chip}>{late ?? look.label}</Chip>
        {row.openEscalations > 0 && (
          <Chip variant="danger">
            {row.openEscalations === 1 ? 'Escalated' : `${row.openEscalations} escalations`}
          </Chip>
        )}
      </span>
    </button>
  )
}

/** A date, or an em dash where there is none — `NOT_SCHEDULED` rows reach these lists too. */
function formatDate(date: string | null | undefined): string {
  if (!date) return 'no date'
  return format(parseISO(date), 'd MMM')
}
