import { Tabs, type TabItem } from '@/components/ui/tabs'

import { AnalyticsTab } from './tabs/analytics/AnalyticsTab'
import { OverviewTab } from './tabs/overview/OverviewTab'
import { TodayTab } from './tabs/today/TodayTab'
import { WeeklyTab } from './tabs/weekly/WeeklyTab'
import { useDashboardFilters } from './useDashboardFilters'

/**
 * Dashboard Rework Dev 1, PR 2 · S-05's shell over its four tabs.
 *
 * <h2>A shell, and nothing else</h2>
 *
 * Every figure, filter and widget that used to live directly on this page
 * moved into {@link AnalyticsTab} verbatim (PR 2's own rule: nothing tidied
 * on the way past). What stays here is only the tab strip and the URL state
 * that drives it — the seam the rest of the feature is built against. After
 * this PR, {@code DashboardPage.tsx} and {@link useDashboardFilters} are
 * frozen: PR 7/8 mount into {@code tabs/today/}, and Dev 2's PR 10/13 mount
 * into {@code tabs/overview/} and {@code tabs/weekly/}, without either of
 * them touching this file again.
 *
 * <h2>`?tab=` is the whole state</h2>
 *
 * Defaults to `today` rather than the previous single-page Analytics view —
 * §S-05's Today tab is the one led with. A tab is a link a colleague can
 * paste, the same reason every other filter on this screen already lives in
 * the URL rather than component state.
 */
const TAB_IDS = ['today', 'overview', 'weekly', 'analytics'] as const
type TabId = (typeof TAB_IDS)[number]

const DEFAULT_TAB: TabId = 'today'

function isTabId(value: string | null): value is TabId {
  return value !== null && (TAB_IDS as readonly string[]).includes(value)
}

export function DashboardPage() {
  const { filters, setFilter } = useDashboardFilters()
  const activeTab: TabId = isTabId(filters.tab) ? filters.tab : DEFAULT_TAB

  const tabs: TabItem[] = [
    { id: 'today', label: "Today's Progress", content: <TodayTab /> },
    { id: 'overview', label: 'Ticket Overview', content: <OverviewTab /> },
    { id: 'weekly', label: 'Weekly Progress', content: <WeeklyTab /> },
    { id: 'analytics', label: 'Analytics', content: <AnalyticsTab /> },
  ]

  return (
    <div className="flex flex-col gap-4 p-6">
      <h1 className="text-xl font-semibold text-[color:var(--text-primary)]">Dashboard</h1>

      <Tabs
        tabs={tabs}
        activeId={activeTab}
        onSelect={(id) => setFilter('tab', id)}
        ariaLabel="Dashboard"
      />
    </div>
  )
}
