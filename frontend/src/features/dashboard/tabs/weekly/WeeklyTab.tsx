import { useSearchParams } from 'react-router-dom'

import { useGetDashboardWeekly } from '@/api/generated/dashboard/dashboard'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'

import { AsOfNotice } from '../../AsOfNotice'
import { DrillDownPanel } from '../../DrillDownPanel'
import { useDashboardFilters } from '../../useDashboardFilters'
import { pickerWeeks, thisWeek, weekFromParam, type Week } from '../../weeklyRange'
import { WeekCaption, WeekPicker } from './WeekPicker'
import { WeeklyCard, WeeklyCardSkeleton } from './WeeklyCard'
import { WeeklySections } from './WeeklySections'

/**
 * S-05 tab 3 · Weekly Progress — the picker, the four cards and the five
 * grouped accordion sections.
 *
 * The sections landed separately as PR 13b, which is why the header above
 * said they would.
 *
 * <h2>`weekStart` lives in the URL, but not in `useDashboardFilters`</h2>
 *
 * `?tab=weekly&weekStart=2026-08-31` has to deep-link — that is in the plan's
 * definition of done. It is read here with `useSearchParams` directly rather
 * than added to `useDashboardFilters`, and that is a design call rather than
 * only a boundary one: the hook holds the filters that mean something on
 * *every* tab — project, assignee, date range — and `weekStart` means nothing
 * on the other three. Putting tab-local state in the shared hook would have
 * every tab reading a key only one of them can act on.
 *
 * It also keeps Dev 1's frozen file frozen, which the split asks for anyway.
 *
 * <h2>An unusable `weekStart` is refused, not corrected</h2>
 *
 * The URL is editable, so `?weekStart=2026-09-02` (a Wednesday) will happen.
 * `weekFromParam` returns null and this renders an explanation with a way back,
 * rather than snapping to the containing Monday. Snapping would show figures
 * for a week the URL does not name — and the endpoint 400s on the same value,
 * so a snapping client would also disagree with the server about which week it
 * is looking at.
 */
export function WeeklyTab() {
  const [params, setParams] = useSearchParams()
  const { filters } = useDashboardFilters()

  // Resolved once per render from the real clock. The pure module reads no
  // clock of its own precisely so this is the only place time enters.
  const now = new Date()
  const week = weekFromParam(params.get('weekStart'), now)

  const selectWeek = (next: Week) => {
    setParams(
      (current) => {
        const updated = new URLSearchParams(current)
        updated.set('weekStart', next.start)
        return updated
      },
      { replace: true },
    )
  }

  if (week === null) {
    return (
      <EmptyState
        title="That is not the start of a week"
        description={
          `A week runs Monday to Sunday, so weekStart has to be a Monday. `
          + `"${params.get('weekStart')}" is not one.`
        }
        action={<Button onClick={() => selectWeek(thisWeek(now))}>Show this week</Button>}
      />
    )
  }

  return <WeeklyTabContent week={week} now={now} onSelectWeek={selectWeek} filters={filters} />
}

function WeeklyTabContent({
  week,
  now,
  onSelectWeek,
  filters,
}: {
  week: Week
  now: Date
  onSelectWeek: (week: Week) => void
  filters: ReturnType<typeof useDashboardFilters>['filters']
}) {
  const { data, isPending, isError, refetch } = useGetDashboardWeekly({
    weekStart: week.start,
    ...(filters.projectId ? { projectId: Number(filters.projectId) } : {}),
    ...(filters.assigneeId ? { assigneeId: Number(filters.assigneeId) } : {}),
  })

  const payload = data?.data
  const cards = payload?.cards ?? []

  return (
    <div className="flex flex-col gap-4">
      <header className="flex flex-wrap items-center gap-3">
        <WeekPicker weeks={pickerWeeks(now)} selected={week} onSelect={onSelectWeek} />
        {/* The server echoes the week back; prefer it over the local guess so a
            disagreement about which week is being shown is visible rather than
            hidden behind a client-side label that is always self-consistent. */}
        <WeekCaption week={payload?.weekStart ? { ...week, start: payload.weekStart, end: payload.weekEnd ?? week.end } : week} />
      </header>

      <AsOfNotice asOf={payload?.asOf} />

      {payload?.unavailableReason ? (
        <EmptyState title="These figures are not available" description={payload.unavailableReason} />
      ) : isError ? (
        <EmptyState
          title="Weekly progress could not be loaded"
          description="The summary tables may not have been built for this week yet."
          action={<Button onClick={() => refetch()}>Retry</Button>}
        />
      ) : (
        <section aria-label="Weekly figures" className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {isPending
            ? Array.from({ length: 4 }, (_, i) => <WeeklyCardSkeleton key={i} />)
            : cards.map((card) => (
                <WeeklyCard
                  key={card.key}
                  cardKey={card.key}
                  label={card.label}
                  value={card.value}
                  unit={card.unit}
                  secondaryValue={card.secondaryValue}
                  secondaryLabel={card.secondaryLabel}
                  deltaPct={card.deltaPct}
                  drillDown={card.drillDown}
                />
              ))}
        </section>
      )}

      {/* The sections render outside the card branch above: they are their own
          live `GET /tickets` queries and stay useful when the summary tables
          have not been built for this week, which is exactly when somebody
          wants to look at the tickets themselves. They are skipped only for an
          out-of-scope project, where the refusal applies to everything.
          `weekStart`/`weekEnd` come from the server's echo where it has
          answered, so a deep link to a past week reads that week. */}
      {!payload?.unavailableReason && (
        <WeeklySections
          scope={{
            weekStart: payload?.weekStart ?? week.start,
            weekEnd: payload?.weekEnd ?? week.end,
            ...(filters.projectId ? { projectId: Number(filters.projectId) } : {}),
            ...(filters.assigneeId ? { assigneeId: Number(filters.assigneeId) } : {}),
          }}
        />
      )}

      {/* Mounted once for the tab rather than per card — one panel showing
          whichever drill-down was opened last, not four competing focus traps.
          Same reason `AnalyticsTab` mounts exactly one. */}
      <DrillDownPanel />
    </div>
  )
}
