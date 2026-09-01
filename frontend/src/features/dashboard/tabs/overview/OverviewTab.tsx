import { Link } from 'react-router-dom'

import { useGetDashboardOverview } from '@/api/generated/dashboard/dashboard'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'

import { AsOfNotice } from '../../AsOfNotice'
import { DrillDownPanel } from '../../DrillDownPanel'
import { useDashboardFilters } from '../../useDashboardFilters'
import { useDrillDownStore } from '../../drillDownStore'
import { StatusDistributionDonut, StatusDistributionDonutSkeleton } from './StatusDistributionDonut'
import { TopAssigneesBar, TopAssigneesBarSkeleton } from './TopAssigneesBar'

/**
 * Dashboard Rework Dev 2 · tab 2 — the four range cards, the Top Assignees
 * bars and the status donut.
 *
 * <h2>The cards and the assignee bars answer different questions</h2>
 *
 * The cards are the selected range: what was reported, is pending, is being
 * worked, was completed between `from` and `to`. The bars underneath are
 * **open state right now**, deliberately not the range — "who is carrying
 * what today" rather than "what happened in the window". Both live under one
 * date filter, so the panel headings say which is which, and
 * `TopAssigneesBar`'s own note repeats it where somebody might otherwise
 * "fix" the inconsistency.
 *
 * <h2>Sub-captions are the screen's, not the server's</h2>
 *
 * The server sends `label` only — "Total", "Pending" — and the prototype puts
 * a line of prose under each figure ("reported in range", "not started yet").
 * That text is presentation: it explains the same number to a reader rather
 * than describing different data, so it belongs here rather than as four more
 * strings on the wire. The `label` itself always comes from the server.
 */

/** Keyed by the contract's four card keys; anything unrecognised simply gets no caption. */
const CARD_CAPTIONS: Record<string, string> = {
  total: 'reported in range',
  pending: 'not started yet',
  'in-progress': 'being worked now',
  completed: 'resolved or closed',
}

/** Only two of the four are coloured in the prototype; the other two stay default ink. */
const CARD_TONES: Record<string, string> = {
  pending: 'var(--warning-text)',
  completed: 'var(--success-text)',
}

export function OverviewTab() {
  const { filters } = useDashboardFilters()

  const { data, isPending, isError, refetch } = useGetDashboardOverview({
    ...(filters.projectId ? { projectId: Number(filters.projectId) } : {}),
    ...(filters.assigneeId ? { assigneeId: Number(filters.assigneeId) } : {}),
    ...(filters.from ? { from: filters.from } : {}),
    ...(filters.to ? { to: filters.to } : {}),
  })

  const payload = data?.data
  const cards = payload?.cards ?? []
  const assignees = payload?.assignees ?? []
  const distribution = payload?.distribution ?? []

  return (
    <div className="flex flex-col gap-4">
      <AsOfNotice asOf={payload?.asOf} />

      {payload?.unavailableReason ? (
        <EmptyState
          title="These figures are not available"
          description={payload.unavailableReason}
        />
      ) : isError ? (
        <EmptyState
          title="Ticket Overview could not be loaded"
          description="The summary tables may not have been built for this range yet."
          action={<Button onClick={() => refetch()}>Retry</Button>}
        />
      ) : (
        <>
          <section
            aria-label="Ticket overview figures"
            className="grid gap-3 grid-cols-[repeat(auto-fit,minmax(200px,1fr))]"
          >
            {isPending
              ? Array.from({ length: 4 }, (_, i) => <OverviewCardSkeleton key={i} />)
              : cards.map((card) => (
                  <OverviewCard
                    key={card.key}
                    cardKey={card.key}
                    label={card.label}
                    value={card.value}
                    drillDown={card.drillDown}
                  />
                ))}
          </section>

          <div className="grid gap-4 lg:grid-cols-2">
            <Panel
              title="Top assignees"
              subtitle="Open tickets per assignee — click any segment for the tickets behind it"
            >
              {isPending ? <TopAssigneesBarSkeleton /> : <TopAssigneesBar assignees={assignees} />}
            </Panel>

            <Panel
              title="Ticket status distribution"
              subtitle="Everything reported in the range — three buckets, no more"
            >
              {isPending ? (
                <StatusDistributionDonutSkeleton />
              ) : (
                <StatusDistributionDonut distribution={distribution} />
              )}
            </Panel>
          </div>
        </>
      )}

      {/* One panel for the tab rather than one per figure — the same reason
          `WeeklyTab` and `AnalyticsTab` each mount exactly one: four competing
          focus traps is not a feature. */}
      <DrillDownPanel />
    </div>
  )
}

function Panel({
  title,
  subtitle,
  children,
}: {
  title: string
  subtitle: string
  children: React.ReactNode
}) {
  return (
    <section
      aria-label={title}
      className="rounded-card border border-[color:var(--border)] bg-[color:var(--bg-surface)] p-4"
    >
      <h3 className="text-sm font-semibold text-[color:var(--text-primary)]">{title}</h3>
      <p className="mb-3 mt-0.5 text-xs text-[color:var(--text-secondary)]">{subtitle}</p>
      {children}
    </section>
  )
}

interface OverviewCardProps {
  cardKey: string
  label: string
  value: number
  drillDown?: string | null
}

/**
 * A single-figure tile, so it follows `WeeklyCard` rather than
 * `TodaySummaryCard`: a `<Link>` whose unmodified primary click opens the S-06
 * panel, leaving ctrl/cmd/shift/middle-click to open a real ticket list in a
 * new tab. Taking that away is removing a browser affordance nobody expects an
 * app to remove.
 */
function OverviewCard({ cardKey, label, value, drillDown }: OverviewCardProps) {
  const openPanel = useDrillDownStore((s) => s.open)
  const caption = CARD_CAPTIONS[cardKey]

  const body = (
    <>
      <span className="text-sm text-[color:var(--text-secondary)]">{label}</span>
      <span
        className="text-2xl font-semibold tabular-nums"
        style={{ color: CARD_TONES[cardKey] ?? 'var(--text-primary)' }}
      >
        {value}
      </span>
      {caption ? (
        <span className="text-xs text-[color:var(--text-secondary)]">{caption}</span>
      ) : null}
    </>
  )

  const className =
    'rounded-card border border-[color:var(--border)] bg-[color:var(--bg-surface)] p-4 ' +
    'flex flex-col gap-1 text-left transition-shadow hover:shadow-sm ' +
    'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 ' +
    'focus-visible:outline-[color:var(--primary)]'

  // A figure with no expressible list is not a link — rendering an anchor to
  // nowhere puts it in the tab order promising something it cannot do.
  if (!drillDown) {
    return (
      <div className={className} aria-label={`${label}: ${value}`}>
        {body}
      </div>
    )
  }

  return (
    <Link
      to={drillDown}
      aria-label={`${label}: ${value}`}
      onClick={(event) => {
        if (event.defaultPrevented) return
        if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
        if (event.button !== 0) return
        event.preventDefault()
        openPanel(drillDown, label, value)
      }}
      className={className}
    >
      {body}
    </Link>
  )
}

function OverviewCardSkeleton() {
  return (
    <div className="rounded-card border border-[color:var(--border)] bg-[color:var(--bg-surface)] p-4 flex flex-col gap-2">
      <Skeleton className="h-4 w-20" />
      <Skeleton className="h-7 w-14" />
      <Skeleton className="h-3 w-24" />
    </div>
  )
}
