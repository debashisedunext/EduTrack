import { useGetDashboardToday } from '@/api/generated/dashboard/dashboard'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'

import { AsOfNotice } from '../../AsOfNotice'
import { DrillDownPanel } from '../../DrillDownPanel'
import { useDashboardFilters } from '../../useDashboardFilters'
import { OpenIssuesByRoleCard, OpenIssuesByRoleCardSkeleton } from './OpenIssuesByRoleCard'
import { TodaySummaryCard, TodaySummaryCardSkeleton } from './TodaySummaryCard'

/**
 * Dashboard Rework Dev 1 · tab 1, PR 7 — the seven `TodaySummaryCard`s and
 * the Open Issues card. The Assignee MIS grid and the six collapsible
 * sections are PR 8; splitting them keeps both PRs inside the ~400-line
 * rule, the same way PR 13 splits its picker+cards from its accordion.
 *
 * <h2>Started Today and Finished Today are not cards</h2>
 *
 * Removed by product decision — the plan is explicit that showing them
 * as cards was reconsidered. They resurface in PR 8 as sections and as MIS
 * columns, never here.
 *
 * <h2>The own-work variant renders fewer cards, not a different screen</h2>
 *
 * `openIssues` is `null` for Developer/QA/Deployment logins — answering "who
 * holds the work" makes no sense when the answer is always "me". The seven
 * summary cards still render; only the eighth card is conditional.
 */
export function TodayTab() {
  const { filters } = useDashboardFilters()
  const { data, isPending, isError, refetch } = useGetDashboardToday({
    ...(filters.projectId ? { projectId: Number(filters.projectId) } : {}),
  })

  const payload = data?.data
  const cards = payload?.cards ?? []

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
          title="Today's Progress could not be loaded"
          description="The summary tables may not have been built for today yet."
          action={<Button onClick={() => refetch()}>Retry</Button>}
        />
      ) : (
        <section
          aria-label="Today's figures"
          className="grid gap-3 grid-cols-[repeat(auto-fit,minmax(238px,1fr))]"
        >
          {isPending ? (
            Array.from({ length: 8 }, (_, i) =>
              i === 7 ? <OpenIssuesByRoleCardSkeleton key={i} /> : <TodaySummaryCardSkeleton key={i} />,
            )
          ) : (
            <>
              {cards.map((card) => (
                <TodaySummaryCard
                  key={card.key}
                  cardKey={card.key}
                  label={card.label}
                  total={card.total}
                  figures={card.figures}
                />
              ))}
              {payload?.openIssues ? (
                <OpenIssuesByRoleCard total={payload.openIssues.total} roles={payload.openIssues.roles} />
              ) : null}
            </>
          )}
        </section>
      )}

      <DrillDownPanel />
    </div>
  )
}
