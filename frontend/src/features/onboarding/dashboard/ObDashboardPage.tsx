import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'

import { useGetObDashboardSummary } from '@/api/generated/onboarding/onboarding'
import type { ObDashboardCard, ObDashboardCardKey } from '@/api/generated/model'
import { Button } from '@/components/ui/button'
import { Tabs, type TabItem } from '@/components/ui/tabs'

import { ObDashboardDrillPanel } from './ObDashboardDrillPanel'
import { ObDashboardStuckPanel } from './ObDashboardStuckPanel'
import { ObDelayedProjectsGrid } from './ObDelayedProjectsGrid'
import { ObImplementorWorkloadGrid } from './ObImplementorWorkloadGrid'
import { ObSummaryTab } from './tabs/summary/ObSummaryTab'

/**
 * B-127/B-128 · what the S-06 slide-over is currently showing, or nothing.
 *
 * One shape for both readers of {@link ObDashboardDrillPanel} — a card tile,
 * which only ever names its own `cardKey`, and {@link ObImplementorWorkloadGrid},
 * which also narrows by `ownerUserId` and supplies its own `title`. A single
 * piece of state and a single panel instance, rather than one panel per
 * caller, is the reuse B-127 built the panel for in the first place. It lives
 * on the page, not inside a tab, because a click from the Summary tab and a
 * click from the Workload tab open the identical panel and neither tab
 * should own an instance the other one also needs.
 */
interface DrillTarget {
  cardKey: ObDashboardCardKey
  ownerUserId?: number
  title?: string
}

/** The four tabs, and the `?tab=` value each one is. */
const TAB_IDS = ['summary', 'stuck', 'delayed', 'workload'] as const
type TabId = (typeof TAB_IDS)[number]

const DEFAULT_TAB: TabId = 'summary'

function isTabId(value: string | null): value is TabId {
  return value !== null && (TAB_IDS as readonly string[]).includes(value)
}

/**
 * B-121 · OB-02, the onboarding dashboard — `/onboarding/dashboard`.
 *
 * <h2>Four tabs, on the ticketing dashboard's own shell</h2>
 *
 * Summary / Where it's stuck / Delayed projects / Implementor workload &amp;
 * performance, mirroring `DashboardPage`'s Today's Progress / Ticket Overview
 * / Weekly Progress / Analytics — the same {@link Tabs} control, the same
 * `?tab=` URL state so a tab is a link a colleague can paste, and the same
 * reason: only the active tab's content mounts, so a manager checking
 * Delayed projects no longer also pays for the stuck-panel and workload-grid
 * requests on every load.
 *
 * <h2>Seven counters, one request</h2>
 *
 * `GET /onboarding/dashboard/summary` is the board's whole first paint. Not
 * seven requests and not a request per card: the contract's own reasoning, and
 * the same call A-073 made for the ticketing dashboard after the per-widget
 * shape cost eleven round trips on load. It stays here rather than moving
 * into the Summary tab because the header's "as of" line reads it too, and
 * that line is not part of any one tab.
 *
 * <h2>Every number is pre-aggregated, and the screen says how old it is</h2>
 *
 * CLAUDE.md forbids a live `COUNT(*)` behind a dashboard, so these come from
 * `ob_dashboard_summary` as B-120's job last wrote it. That makes them up to
 * one refresh interval stale by design — and a board that cannot say so invites
 * somebody to compare it against a client list and file a bug about the
 * difference. Hence the "as of" line, which is `computedAt` and nothing
 * cleverer.
 *
 * A **null** `computedAt` is a different claim and gets a different screen: the
 * refresh has never run, which A-108 makes correct rather than broken for a
 * deployment's first days. An empty board there is honest; a board of zeroes
 * would not be.
 *
 * <h2>Every card opens the S-06 slide-over — B-127</h2>
 *
 * `drill` is the whole handoff: a tile's `onOpen` sets it, and
 * {@link ObDashboardDrillPanel} reads it to fetch and render
 * `listObDashboardCardItems`. Nothing about *which* card is decided twice —
 * the tile passes its own `card.key` straight through, so the panel opens
 * exactly the card that was clicked.
 */
export function ObDashboardPage() {
  const { data, isPending, isError } = useGetObDashboardSummary()
  const [drill, setDrill] = useState<DrillTarget | null>(null)
  const [searchParams, setSearchParams] = useSearchParams()

  const summary = data?.data
  const cards = summary?.cards ?? []
  const activeTab: TabId = isTabId(searchParams.get('tab')) ? (searchParams.get('tab') as TabId) : DEFAULT_TAB

  function selectTab(id: string) {
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev)
        next.set('tab', id)
        return next
      },
      { replace: true },
    )
  }

  function openDrill(card: ObDashboardCard) {
    setDrill({ cardKey: card.key })
  }

  const tabs: TabItem[] = [
    {
      id: 'summary',
      label: 'Summary',
      content: <ObSummaryTab cards={cards} isPending={isPending} isError={isError} onOpen={openDrill} />,
    },
    { id: 'stuck', label: "Where it's stuck", content: <ObDashboardStuckPanel /> },
    { id: 'delayed', label: 'Delayed projects', content: <ObDelayedProjectsGrid /> },
    {
      id: 'workload',
      label: 'Implementor workload & performance',
      content: <ObImplementorWorkloadGrid onDrill={setDrill} />,
    },
  ]

  return (
    <div className="mx-auto flex w-full max-w-[1280px] flex-col gap-5 p-6">
      <header className="flex flex-wrap items-start gap-3">
        <div>
          <h1 className="text-2xl font-semibold leading-8 text-content">Onboarding dashboard</h1>
          <p className="mt-0.5 text-xs text-content-muted">
            Every client in flight, where it is, and where it's stuck.
          </p>
          {summary && <AsOf computedAt={summary.computedAt} appliedScope={summary.appliedScope} />}
        </div>
        <Button asChild size="sm" className="ml-auto">
          <Link to="/onboarding/clients/new">+ Board a new client</Link>
        </Button>
      </header>

      <Tabs tabs={tabs} activeId={activeTab} onSelect={selectTab} ariaLabel="Onboarding dashboard" />

      <ObDashboardDrillPanel
        cardKey={drill?.cardKey ?? null}
        onClose={() => setDrill(null)}
        title={drill?.title}
        ownerUserId={drill?.ownerUserId}
      />
    </div>
  )
}

/**
 * How stale the board is, and what it counted.
 *
 * Both in one line because they answer the same question — "why does this not
 * match what I am looking at" — and a user who has to hunt for them separately
 * will conclude the screen is wrong instead.
 *
 * `appliedScope` is the server's sentence, never re-derived here. CLAUDE.md's
 * rule is that scope is resolved server-side and never by a frontend filter,
 * and A-056 makes the narrower point this line depends on: saying it explicitly
 * is what stops the SPA re-deriving the role rule for itself.
 */
function AsOf({
  computedAt,
  appliedScope,
}: {
  computedAt?: string | null
  appliedScope?: string
}) {
  if (!computedAt) {
    // Not "as of never". The cards each carry their own sentence about it; this
    // line simply has nothing to add, and an "as of —" would read as a bug.
    return appliedScope ? (
      <p className="text-sm text-content-muted">Counting {appliedScope}</p>
    ) : null
  }

  return (
    <p className="text-sm text-content-muted">
      {appliedScope ? `Counting ${appliedScope} · ` : ''}
      as of{' '}
      <time dateTime={computedAt}>
        {new Date(computedAt).toLocaleString(undefined, {
          dateStyle: 'medium',
          timeStyle: 'short',
        })}
      </time>
    </p>
  )
}
