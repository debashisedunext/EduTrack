import * as React from 'react'
import { useSearchParams } from 'react-router-dom'

import { useGetDashboardSummary } from '@/api/generated/dashboard/dashboard'
import { useListProjects } from '@/api/generated/projects/projects'
import { useListUsers } from '@/api/generated/users/users'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { FilterDropdown } from '@/components/ui/filter-dropdown'

import { DashboardDateRange, type DateRange } from './DashboardDateRange'
import { DashboardWidgets } from './DashboardWidgets'
import { KpiCard, KpiCardSkeleton } from './KpiCard'

/**
 * A-054 shell + A-055 cards · S-05.
 *
 * <h2>Filter state lives in the URL</h2>
 *
 * Not in component state. A dashboard somebody has narrowed to one project and
 * a fortnight is a thing they send to a colleague, bookmark, or return to after
 * clicking a card and coming back — and all three of those break the moment the
 * filters are held in React. The ticket list (C-014) made the same choice for
 * the same reason, so the two screens behave alike.
 *
 * <h2>What is not here</h2>
 *
 * Widgets 13–15 — the calendar heatmap, the SLA gauge and the project treemap —
 * are A-057, and append to `DashboardWidgets` without anything moving. A-056
 * added widgets 7–12 into the space this grid was laid out to take.
 *
 * The widget block reads the same URL filter state as the cards, so narrowing
 * to a project narrows the whole screen at once and the result is still a link
 * somebody can send. `assigneeId` is deliberately **not** passed down: the
 * widget endpoint does not accept it (the contract gives it `projectId`, `from`
 * and `to` only), and the two resource widgets are already scoped by role —
 * a delivery role sees their own line and nobody else's.
 */

/** §S-05 colours widgets 4 and 5. The rest are neutral. */
const TONE: Record<string, 'critical' | 'delayed'> = {
  critical: 'critical',
  delayed: 'delayed',
}

export function DashboardPage() {
  const [params, setParams] = useSearchParams()

  const projectId = params.get('projectId')
  const assigneeId = params.get('assigneeId')
  const range: DateRange = { from: params.get('from'), to: params.get('to') }

  const setParam = React.useCallback(
    (key: string, value: string | null) => {
      setParams(
        (current) => {
          const next = new URLSearchParams(current)
          if (value === null || value === '') {
            next.delete(key)
          } else {
            next.set(key, value)
          }
          return next
        },
        { replace: true },
      )
    },
    [setParams],
  )

  // The masters behind the two dropdowns. Both are bounded lists the server
  // already scopes, so the caller only ever sees projects and people they could
  // filter by anyway.
  const { data: projectsData } = useListProjects({ isActive: true, limit: 200 })
  const { data: usersData } = useListUsers({ limit: 200 })

  const projects = projectsData?.data ?? []
  const users = usersData?.data ?? []

  const { data, isPending, isError, refetch, isFetching } = useGetDashboardSummary({
    ...(projectId ? { projectId: Number(projectId) } : {}),
    ...(assigneeId ? { assigneeId: Number(assigneeId) } : {}),
    ...(range.from ? { from: range.from } : {}),
    ...(range.to ? { to: range.to } : {}),
  })

  const cards = data?.data?.cards ?? []
  const asOf = data?.data?.asOf

  return (
    <div className="flex flex-col gap-4 p-6">
      <header className="flex flex-wrap items-center gap-3">
        <h1 className="text-xl font-semibold text-[color:var(--text-primary)]">Dashboard</h1>

        <div className="ml-auto flex flex-wrap items-center gap-2">
          <FilterDropdown
            label="Project"
            options={projects}
            value={projects.find((p) => String(p.id) === projectId) ?? null}
            onChange={(p) => setParam('projectId', p ? String(p.id) : null)}
            getKey={(p) => String(p.id)}
            getLabel={(p) => p.name ?? `#${p.id}`}
            searchable
          />
          <DashboardDateRange
            value={range}
            onChange={(next) => {
              setParam('from', next.from)
              setParam('to', next.to)
            }}
          />
          <FilterDropdown
            label="Resource"
            options={users}
            value={users.find((u) => String(u.id) === assigneeId) ?? null}
            onChange={(u) => setParam('assigneeId', u ? String(u.id) : null)}
            getKey={(u) => String(u.id)}
            getLabel={(u) =>
              // B-027 · `displayName`, not `fullName`. `User` is
              // `UserRef & UserAllOf` and neither half has ever carried a
              // `fullName` — that is the *column* name (`users.full_name`),
              // which the contract renames on the wire. `develop` does not
              // compile without this. **Stream A's file, flagged.**
              u.displayName || u.username || `#${u.id}`
            }
            searchable
          />
          <Button variant="secondary" onClick={() => refetch()} disabled={isFetching}>
            {isFetching ? 'Refreshing…' : 'Refresh'}
          </Button>
        </div>
      </header>

      <AsOfNotice asOf={asOf} />

      {isError ? (
        <EmptyState
          title="The dashboard could not be loaded"
          description="The summary tables may not have been built yet. Try refreshing."
          action={<Button onClick={() => refetch()}>Retry</Button>}
        />
      ) : (
        <section
          aria-label="Key figures"
          className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6"
        >
          {isPending
            ? Array.from({ length: 6 }, (_, i) => <KpiCardSkeleton key={i} />)
            : cards.map((card) => (
                <KpiCard
                  key={card.key}
                  label={card.label ?? card.key ?? ''}
                  value={card.value ?? 0}
                  deltaPct={card.deltaPct}
                  sparkline={card.sparkline ?? []}
                  drillDown={card.drillDown ?? '/tickets'}
                  tone={TONE[card.key ?? ''] ?? 'neutral'}
                />
              ))}
        </section>
      )}

      {/* Rendered even when the cards failed. The six widgets are six
          independent requests against the same summary tables, and a failure in
          the summary read says nothing about whether they can be answered. */}
      <DashboardWidgets
        params={{
          ...(projectId ? { projectId: Number(projectId) } : {}),
          ...(range.from ? { from: range.from } : {}),
          ...(range.to ? { to: range.to } : {}),
        }}
      />
    </div>
  )
}

/**
 * These figures are up to five minutes old by design (A-051), and the server
 * says exactly how old. Surfaced rather than hidden: a dashboard that silently
 * presents stale numbers as live is how somebody makes a call on a figure that
 * moved four minutes ago.
 */
function AsOfNotice({ asOf }: { asOf?: string }) {
  if (!asOf) {
    // No computed_at means A-051 has not run for this window at all — which is
    // a different statement from "zero tickets", and worth saying plainly.
    return (
      <p className="text-xs text-[color:var(--warning-text)]">
        These figures have not been computed yet. The summary worker runs every five minutes.
      </p>
    )
  }
  return (
    <p className="text-xs text-[color:var(--text-secondary)]">
      Figures as at {new Date(asOf).toLocaleString()}. Refreshed every five minutes.
    </p>
  )
}
