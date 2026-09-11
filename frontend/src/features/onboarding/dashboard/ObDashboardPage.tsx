import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'

import { useGetObDashboardSummary } from '@/api/generated/onboarding/onboarding'
import type { ObDashboardCard, ObDashboardCardKey } from '@/api/generated/model'
import { Tabs, type TabItem } from '@/components/ui/tabs'

import { ObDashboardCardRow } from './ObDashboardCardRow'
import { ObDashboardDrillPanel } from './ObDashboardDrillPanel'
import { ObDashboardRagBoard } from './ObDashboardRagBoard'
import { ObDashboardStuckPanel } from './ObDashboardStuckPanel'
import { ObDelayedProjectsGrid } from './ObDelayedProjectsGrid'
import { ObImplementorWorkloadGrid } from './ObImplementorWorkloadGrid'

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
 * <h2>The counters, then four tabs</h2>
 *
 * {@link ObDashboardCardRow} first and always, then Summary / Where it's
 * stuck / Delayed projects / Implementor workload &amp; performance on the
 * ticketing dashboard's own {@link Tabs} shell — the same `?tab=` URL state so
 * a tab is a link a colleague can paste, and the same reason for tabbing at
 * all: only the active tab's content mounts, so a manager checking Delayed
 * projects no longer also pays for the stuck-panel and workload-grid requests
 * on every load.
 *
 * The cards sit **above** the strip rather than inside Summary because they
 * are the page's standing figures, not one tab's content — the row's own file
 * carries that argument. What is left in Summary is the RAG board, which is
 * the Summary *view* of the same clients the other three tabs cut differently.
 *
 * <h2>No page header</h2>
 *
 * No title, no strapline, no "as of" line and no boarding button. The module's
 * own shell already names the screen in the sidebar, and the six counters are
 * a faster answer to "what am I looking at" than a sentence describing them.
 * Boarding a client is OB-03's action and lives on OB-03's own page, where the
 * list it adds to is visible. The `h1` survives for screen readers, which have
 * no sidebar to read the page's name from.
 *
 * <h2>Seven counters, one request</h2>
 *
 * `GET /onboarding/dashboard/summary` is the board's whole first paint. Not
 * seven requests and not a request per card: the contract's own reasoning, and
 * the same call A-073 made for the ticketing dashboard after the per-widget
 * shape cost eleven round trips on load. Six of the seven are drawn — `live`
 * is filtered in `obDashboardCards.ts`, with the reasoning there.
 *
 * <h2>Every number is pre-aggregated</h2>
 *
 * CLAUDE.md forbids a live `COUNT(*)` behind a dashboard, so these come from
 * `ob_dashboard_summary` as B-120's job last wrote it. That makes them up to
 * one refresh interval stale by design. The line that said so came off with
 * the rest of the header; each tile still carries its own caveat
 * (`countIsUpperBound`, `unavailableReason`), which is where a reader
 * questioning a specific number actually looks.
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

  const cards = data?.data?.cards ?? []
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
      content: <ObDashboardRagBoard />,
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
      {/* Named for screen readers only — see the docstring's "No page header". */}
      <h1 className="sr-only">Onboarding dashboard</h1>

      <ObDashboardCardRow
        cards={cards}
        isPending={isPending}
        isError={isError}
        onOpen={openDrill}
      />

      <Tabs
        tabs={tabs}
        activeId={activeTab}
        onSelect={selectTab}
        ariaLabel="Onboarding dashboard"
        variant="segmented"
      />

      <ObDashboardDrillPanel
        cardKey={drill?.cardKey ?? null}
        onClose={() => setDrill(null)}
        title={drill?.title}
        ownerUserId={drill?.ownerUserId}
      />
    </div>
  )
}
