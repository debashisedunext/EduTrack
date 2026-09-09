import { useListObImplementorWorkload } from '@/api/generated/onboarding/onboarding'
import type { ObDashboardCardKey, ObImplementorWorkload } from '@/api/generated/model'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'

/** Headcount fits one page on any real deployment; see the class note. */
const PAGE_LIMIT = 200

export interface ObImplementorWorkloadGridProps {
  /**
   * Opens {@link ObDashboardDrillPanel} narrowed to one implementor — the
   * contract's own note on this route's `ownerUserId` parameter, and B-127's
   * stated reason that panel takes the prop at all. `ObDashboardPage` owns the
   * one panel instance every card tile and this grid share.
   */
  onDrill: (target: { cardKey: ObDashboardCardKey; ownerUserId: number; title: string }) => void
}

/**
 * B-128 · plan §9's second grid — `GET /onboarding/dashboard/implementor-workload`.
 *
 * <h2>One row per implementor, the bench included</h2>
 *
 * The route's own contract: an implementor holding a live grant gets a row
 * even at zero open clients, so a fully-delivered person reads as clear
 * rather than as absent. Nothing here filters that out.
 *
 * <h2>Six counters, and only three of them open into the slide-over</h2>
 *
 * `listObDashboardCardItems` — the route behind {@link ObDashboardDrillPanel}
 * — answers seven fixed cards, none of which is "on track" or "not started"
 * or "ahead of schedule" for one owner. Rather than invent a card the
 * contract does not have, or wire a button to a filter that would silently
 * show the wrong rows, {@link COLUMN_CARD} only maps the two counters with an
 * exact match — `delayed` → `overdue-clients`, `atRisk` → `at-risk` — plus
 * `clientsOpen` and the four remaining open-work counters, which open
 * `ongoing-projects` narrowed to this owner: not exact, but an honest "here
 * is everything they are carrying", which is what a reader clicking a
 * workload number wants next.
 *
 * <h2>The three completion counters are not columns here at all</h2>
 *
 * Plan §9's own line for this grid names six status counts and a score —
 * `completedOnTime`/`completedEarly`/`completedLate` are not among them. They
 * are `performanceScore`'s inputs, computed server-side into the single
 * number {@link PerformanceChip} renders; this component does not read the
 * three counters itself. Nothing here would be clickable if it did: every
 * card the slide-over can open lists open items, and a click into settled
 * work would show nothing and read as broken.
 *
 * <h2>No `statDate` or `includeInactive` control yet</h2>
 *
 * Both are on the contract and neither is on OB-02's v1.2 design — plan §9
 * names the columns, not a history picker. This reads today's board only;
 * a follow-up if the trend view is wanted.
 */
export function ObImplementorWorkloadGrid({ onDrill }: ObImplementorWorkloadGridProps) {
  const { data, isPending, isError } = useListObImplementorWorkload({ limit: PAGE_LIMIT })

  const rows = data?.data ?? []
  const hasMore = data?.meta?.hasMore ?? false

  return (
    <section aria-labelledby="ob-implementor-workload-heading" className="flex flex-col gap-2">
      <h2 id="ob-implementor-workload-heading" className="text-base font-semibold text-content">
        Implementor workload &amp; performance
      </h2>

      {isPending ? (
        <div className="flex flex-col gap-2">
          {Array.from({ length: 4 }, (_, i) => (
            <Skeleton key={i} className="h-12 w-full" />
          ))}
        </div>
      ) : isError ? (
        <EmptyState
          title="The workload grid could not be loaded"
          description="Refresh to try again."
        />
      ) : rows.length === 0 ? (
        <EmptyState
          title="No implementor holds an onboarding grant yet"
          description="Grant OB_STEP_OWNER to at least one user to see them here, the bench included."
        />
      ) : (
        <div className="overflow-x-auto rounded-card border border-border">
          <table className="w-full text-left text-sm">
            <caption className="sr-only">
              One row per implementor, including those carrying no clients today.
            </caption>
            <thead className="text-xs text-content-muted">
              <tr>
                <th scope="col" className="py-2 pl-3 font-medium">Implementor</th>
                <th scope="col" className="py-2 font-medium text-right">Open</th>
                <th scope="col" className="py-2 font-medium text-right">On track</th>
                <th scope="col" className="py-2 font-medium text-right whitespace-nowrap">Not started</th>
                <th scope="col" className="py-2 font-medium text-right">Delayed</th>
                <th scope="col" className="py-2 font-medium text-right">At risk</th>
                <th scope="col" className="py-2 font-medium text-right whitespace-nowrap">Blocked / waiting</th>
                <th scope="col" className="py-2 font-medium text-right whitespace-nowrap">Ahead</th>
                <th scope="col" className="py-2 pr-3 font-medium text-right">Performance</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <WorkloadRow key={row.user.id} row={row} onDrill={onDrill} />
              ))}
            </tbody>
          </table>
        </div>
      )}

      {hasMore && (
        <p className="text-xs text-content-muted">
          Showing the first {PAGE_LIMIT} implementors.
        </p>
      )}
    </section>
  )
}

/** See the class note on why only these five counters carry a card key. */
const COLUMN_CARD: Record<'clientsOpen' | 'onTrack' | 'notStarted' | 'delayed' | 'atRisk'
  | 'blockedWaiting' | 'aheadOfSchedule', ObDashboardCardKey> = {
  clientsOpen: 'ongoing-projects',
  onTrack: 'ongoing-projects',
  notStarted: 'ongoing-projects',
  delayed: 'overdue-clients',
  atRisk: 'at-risk',
  blockedWaiting: 'ongoing-projects',
  aheadOfSchedule: 'ongoing-projects',
}

function WorkloadRow({ row, onDrill }: { row: ObImplementorWorkload; onDrill: ObImplementorWorkloadGridProps['onDrill'] }) {
  const name = row.user.displayName
  const open = (column: keyof typeof COLUMN_CARD, label: string) =>
    onDrill({ cardKey: COLUMN_CARD[column], ownerUserId: row.user.id, title: `${name} · ${label}` })

  return (
    <tr className="border-t border-border align-top">
      <td className="py-2 pl-3 pr-3 whitespace-nowrap font-medium text-content">{name}</td>
      <Cell value={row.clientsOpen} onClick={() => open('clientsOpen', 'Everything open')} label={`${name}'s open clients`} />
      <Cell value={row.onTrack} onClick={() => open('onTrack', 'On track')} label={`${name}'s on-track clients`} />
      <Cell value={row.notStarted} onClick={() => open('notStarted', 'Not started')} label={`${name}'s not-started clients`} />
      <Cell
        value={row.delayed}
        tone={row.delayed > 0 ? 'danger' : undefined}
        onClick={() => open('delayed', 'Delayed')}
        label={`${name}'s delayed clients`}
      />
      <Cell
        value={row.atRisk}
        tone={row.atRisk > 0 ? 'warning' : undefined}
        onClick={() => open('atRisk', 'At risk')}
        label={`${name}'s at-risk clients`}
      />
      <Cell
        value={row.blockedWaiting}
        onClick={() => open('blockedWaiting', 'Blocked or waiting')}
        label={`${name}'s blocked or waiting clients`}
      />
      <Cell value={row.aheadOfSchedule} onClick={() => open('aheadOfSchedule', 'Ahead of schedule')} label={`${name}'s clients ahead of schedule`} />
      <td className="py-2 pr-3 text-right whitespace-nowrap">
        <PerformanceChip score={row.performanceScore} />
      </td>
    </tr>
  )
}

/**
 * One clickable count. A `<button>` inside the cell, not `onClick` on the
 * `<tr>` — the ribbon and the drill panel's own row already keep pointer and
 * keyboard reach equivalent this way, and a whole-row click here would make
 * every one of seven numbers open the same, least-specific card.
 */
function Cell({
  value,
  onClick,
  label,
  tone,
}: {
  value: number
  onClick: () => void
  label: string
  tone?: 'danger' | 'warning'
}) {
  return (
    <td className="py-1 pr-3 text-right">
      <button
        type="button"
        onClick={onClick}
        className={`rounded-sm px-1.5 py-0.5 tabular-nums underline-offset-2 hover:underline
                    focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2
                    focus-visible:outline-primary
                    ${tone === 'danger' ? 'text-danger-text' : tone === 'warning' ? 'text-warning-text' : 'text-content'}`}
        aria-label={`${label}: ${value}. Open the matching clients.`}
      >
        {value}
      </button>
    </td>
  )
}

function PerformanceChip({ score }: { score: number | null | undefined }) {
  if (score == null) {
    return <span className="text-content-muted">—</span>
  }
  const variant = score >= 85 ? 'success' : score >= 60 ? 'medium' : 'critical'
  return <Chip variant={variant}>{score.toFixed(1)}</Chip>
}
