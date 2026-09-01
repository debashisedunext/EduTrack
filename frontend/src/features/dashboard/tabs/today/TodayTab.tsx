import { useGetDashboardToday } from '@/api/generated/dashboard/dashboard'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'

import { AsOfNotice } from '../../AsOfNotice'
import { DrillDownPanel } from '../../DrillDownPanel'
import { useDashboardFilters } from '../../useDashboardFilters'
import { AssigneeMisTable } from './AssigneeMisTable'
import { OpenIssuesByRoleCard, OpenIssuesByRoleCardSkeleton } from './OpenIssuesByRoleCard'
import { TodaySections } from './TodaySections'
import { todayFromAsOf } from './todaySectionQueries'
import { TodaySummaryCard, TodaySummaryCardSkeleton } from './TodaySummaryCard'

/**
 * Dashboard Rework Dev 1 · tab 1 — the seven `TodaySummaryCard`s, the Open
 * Issues card (PR 7), the Assignee MIS grid and the six collapsible sections
 * (PR 8).
 *
 * <h2>Started Today and Finished Today are not cards</h2>
 *
 * Removed by product decision — the plan is explicit that showing them
 * as cards was reconsidered. They resurface here as sections and as MIS
 * columns, never as cards.
 *
 * <h2>The own-work variant renders fewer cards and no MIS grid, not a different screen</h2>
 *
 * `openIssues` is `null` and `resources` is empty for Developer/QA/Deployment
 * logins — answering "who holds the work" and "how is each resource doing"
 * makes no sense when the answer is always "me". The seven summary cards and
 * the six sections still render; the eighth card and the MIS grid are the
 * only conditional pieces. The sections need no variant branching of their
 * own — `GET /tickets` is row-scoped server-side regardless of who is asking.
 */
export function TodayTab() {
  const { filters } = useDashboardFilters()
  const projectId = filters.projectId ? Number(filters.projectId) : undefined
  const { data, isPending, isError, refetch } = useGetDashboardToday({
    ...(projectId ? { projectId } : {}),
  })

  const payload = data?.data
  const cards = payload?.cards ?? []
  const resources = payload?.resources ?? []

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
        <>
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

          {!isPending && (
            <>
              {resources.length > 0 && <AssigneeMisTable rows={resources} />}
              <TodaySections scope={{ today: todayFromAsOf(payload?.asOf), projectId }} />
            </>
          )}
        </>
      )}

      <DrillDownPanel />
    </div>
  )
}
