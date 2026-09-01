import * as React from 'react'

import type { GetDashboardWidgetParams } from '@/api/generated/model'

import { DashboardWidgetBatch } from './DashboardWidgetBatch'
import { useDashboardWidgetPreferencesStore } from './dashboardWidgetPreferencesStore'
import type { WidgetKey, WidgetSeries } from './WidgetFrame'
import { WidgetFrame } from './WidgetFrame'
import { useDashboardVariant } from './useDashboardVariant'
import { widgetDefinition, widgetTitle } from './widgetCatalog'
import { AgingBuckets } from './charts/AgingBuckets'
import { CalendarHeatmap } from './charts/CalendarHeatmap'
import { ClientVolumeBar } from './charts/ClientVolumeBar'
import { ModuleOpenBar } from './charts/ModuleOpenBar'
import { DailyStackedArea } from './charts/DailyStackedArea'
import { HandoffLatencyLine } from './charts/HandoffLatencyLine'
import { PriorityBar } from './charts/PriorityBar'
import { ProjectTreemap } from './charts/ProjectTreemap'
import { ResourceLoadBar } from './charts/ResourceLoadBar'
import { ReworkPanel } from './charts/ReworkPanel'
import { SlaGauge } from './charts/SlaGauge'
import { StageDurationBar } from './charts/StageDurationBar'
import { StageFunnel } from './charts/StageFunnel'
import { TypeDonut } from './charts/TypeDonut'
import { VelocityLines } from './charts/VelocityLines'

/**
 * A-056 · §S-05's charts, in the two-column layout the blueprint draws.
 *
 * <h2>Six requests, not one</h2>
 *
 * Each widget fetches itself. That is deliberate and it is what
 * `GET /dashboard/widget/{widgetKey}` was shaped for: one widget failing leaves
 * the others standing, each carries its own `ETag` so an unchanged chart costs a
 * 304, and a role that cannot be answered for one widget still gets the rest. A
 * single combined endpoint would have made the whole block one all-or-nothing
 * request whose slowest query set the latency for every chart in it.
 *
 * <h2>A-062 · the developer variant is this list, shorter</h2>
 *
 * §S-05: the Developer's dashboard "shows only widgets 1–6, 9, 12". Cards 1–6
 * are the row above; 9 and 12 are the two drawn when the variant is `own-work`,
 * and the other twelve are not requested at all.
 *
 * Before this, a delivery role loaded all of them and got panel after panel of
 * "this breakdown is not kept per resource" — each an accurate sentence, and
 * together a screen that reads as broken. The sentences stay, because they are
 * right for a PM who has filtered to a resource and for anybody who reaches a
 * widget another way; what changed is that the developer dashboard no longer
 * asks questions it already knows have no answer.
 *
 * **Widgets 10 and 13 are omitted although they would answer.** A-057 gave the
 * heatmap a per-resource measure ("tickets you closed") and widget 10 renders a
 * delivery role their own single bar. Both are left out because §S-05 names the
 * subset with the word "only" — a deliberate omission, not an oversight, and a
 * one-line change here if the variant should grow.
 *
 * <h2>The grid is drawn from an order, not written in one</h2>
 *
 * This file used to hold fourteen literal `WidgetFrame` elements and the order
 * they appeared in the source was the order they appeared on screen. Letting
 * somebody arrange their own dashboard meant giving that up: the metadata moved
 * to `widgetCatalog`, the drawings stayed here as `RENDERERS`, and the grid
 * below is a `map` over whatever order the preferences store holds.
 *
 * Two filters are applied to that order and their sequence matters: the reader's
 * variant first (what their role's tables can answer at all), then what they
 * have hidden. One list, filtered twice, is also why every move is made **by
 * key** rather than by index — see `moveWidgetInOrder`.
 */

/**
 * The drawing for each key. The frame decides whether to call it at all —
 * loading, error, unavailable and empty are all its business, and a renderer
 * only ever sees series it can draw.
 */
const RENDERERS: Record<WidgetKey, (series: WidgetSeries[]) => React.ReactNode> = {
  'type-donut': (series) => <TypeDonut series={series} />,
  'daily-stacked': (series) => <DailyStackedArea series={series} />,
  velocity: (series) => <VelocityLines series={series} />,
  'resource-load': (series) => <ResourceLoadBar series={series} />,
  'priority-bar': (series) => <PriorityBar series={series} />,
  'aging-buckets': (series) => <AgingBuckets series={series} />,
  'calendar-heatmap': (series) => <CalendarHeatmap series={series} />,
  'sla-gauge': (series) => <SlaGauge series={series} />,
  'project-treemap': (series) => <ProjectTreemap series={series} />,
  'client-volume': (series) => <ClientVolumeBar series={series} />,
  'stage-funnel': (series) => <StageFunnel series={series} />,
  rework: (series) => <ReworkPanel series={series} />,
  'stage-duration': (series) => <StageDurationBar series={series} />,
  'handoff-latency': (series) => <HandoffLatencyLine series={series} />,
  // Dashboard Rework Dev 2, PR 14 · widget 15.
  'module-open': (series) => <ModuleOpenBar series={series} />,
}

/**
 * A-073 · the keys each variant can draw, listed once so the batch asks for
 * exactly what is rendered.
 *
 * Exported rather than kept module-private: `DashboardWidgetChooserMenu` reads
 * both to decide which catalogue entries are worth offering a checkbox for. A
 * Developer's dashboard only ever renders the two keys in `OWN_WORK_KEYS` — a
 * settings menu listing all fourteen regardless would let somebody toggle twelve
 * checkboxes that do nothing, on the one screen where visibly doing nothing
 * reads as broken rather than as "not for you".
 *
 * These are a *membership* test now and no longer a running order; the order
 * comes from the store. Both still list their keys in §S-05 sequence, which is
 * the same sequence `widgetCatalog` defaults to — worth keeping so the three
 * files read alike, and load-bearing in none of them.
 */
export const OWN_WORK_KEYS = ['velocity', 'aging-buckets'] as const satisfies readonly WidgetKey[]

export const FULL_KEYS = [
  'type-donut',
  'daily-stacked',
  'velocity',
  'resource-load',
  'priority-bar',
  'aging-buckets',
  'calendar-heatmap',
  'sla-gauge',
  'project-treemap',
  'client-volume',
  // A-058 · widgets 16–19.
  'stage-funnel',
  'rework',
  'stage-duration',
  'handoff-latency',
  // Dashboard Rework Dev 2, PR 14. Not in OWN_WORK_KEYS: module_daily_stats is
  // keyed by project and module, and a delivery role's dashboard reads figures
  // keyed by person — the service answers that variant with a sentence rather
  // than a chart, and offering the tile would be a checkbox that does nothing.
  'module-open',
] as const satisfies readonly WidgetKey[]

export function DashboardWidgets({ params }: { params: GetDashboardWidgetParams }) {
  const variant = useDashboardVariant()
  // The settings menu's other half. `DashboardWidgetChooserMenu` writes this
  // store; reading it here rather than through a prop keeps `DashboardWidgets`'s
  // own signature — and every existing caller and test — untouched, since the
  // defaults (nothing hidden, catalogue order) reproduce the original grid
  // exactly.
  const hiddenWidgets = useDashboardWidgetPreferencesStore((s) => s.hiddenWidgets)
  const widgetOrder = useDashboardWidgetPreferencesStore((s) => s.widgetOrder)
  const moveWidget = useDashboardWidgetPreferencesStore((s) => s.moveWidget)

  // Which frame is in flight, held here rather than in each frame: a drop is the
  // one event that needs to know about two cards at once, and the target card is
  // not the one that started the gesture.
  const [draggingKey, setDraggingKey] = React.useState<WidgetKey | null>(null)
  // Which card the pointer is over, so exactly one drop indicator is drawn.
  const [dragOverKey, setDragOverKey] = React.useState<WidgetKey | null>(null)
  const [announcement, setAnnouncement] = React.useState('')

  const endDrag = () => {
    setDraggingKey(null)
    setDragOverKey(null)
  }

  const ownWork = variant === 'own-work'
  const variantKeys: readonly WidgetKey[] = ownWork ? OWN_WORK_KEYS : FULL_KEYS
  const visibleKeys = widgetOrder.filter(
    (key) => variantKeys.includes(key) && !hiddenWidgets.includes(key),
  )

  const announce = (key: WidgetKey, position: number) => {
    setAnnouncement(
      `${widgetTitle(key, ownWork)} moved to position ${position} of ${visibleKeys.length}.`,
    )
  }

  /** Step one place through the *visible* list, so a hidden widget is not a dead press. */
  const step = (key: WidgetKey, delta: -1 | 1) => {
    const index = visibleKeys.indexOf(key)
    const target = visibleKeys[index + delta]
    if (!target) return
    moveWidget(key, target)
    announce(key, index + delta + 1)
  }

  const dropOn = (target: WidgetKey) => {
    if (draggingKey && draggingKey !== target) {
      moveWidget(draggingKey, target)
      announce(draggingKey, visibleKeys.indexOf(target) + 1)
    }
    endDrag()
  }

  return (
    <DashboardWidgetBatch keys={visibleKeys} params={params}>
      {/* Every move is announced, so the keyboard path is not a silent one. */}
      <p aria-live="polite" className="sr-only">
        {announcement}
      </p>

      <div className="grid gap-3 xl:grid-cols-2">
        {visibleKeys.map((key, index) => (
          <WidgetFrame
            key={key}
            widgetKey={key}
            title={widgetTitle(key, ownWork)}
            categoryLabel={widgetDefinition(key).categoryLabel}
            wide={widgetDefinition(key).wide}
            params={params}
            reorder={{
              position: index + 1,
              total: visibleKeys.length,
              onMoveUp: index > 0 ? () => step(key, -1) : undefined,
              onMoveDown: index < visibleKeys.length - 1 ? () => step(key, 1) : undefined,
              onDragStart: () => setDraggingKey(key),
              onDragEnd: endDrag,
              onDropHere: () => dropOn(key),
              onDragOverHere: () => setDragOverKey(key),
              // Guarded, because dragging into a child element fires `dragleave`
              // on the card the pointer is still inside — clearing unconditionally
              // would make the indicator flicker across every chart it crosses.
              onDragLeaveHere: () => setDragOverKey((over) => (over === key ? null : over)),
              isDragging: draggingKey === key,
              isDragActive: draggingKey !== null,
              isDropTarget: dragOverKey === key && draggingKey !== key,
            }}
          >
            {RENDERERS[key]}
          </WidgetFrame>
        ))}

        {visibleKeys.length === 0 && <NoWidgetsSelectedNotice />}
      </div>
    </DashboardWidgetBatch>
  )
}

/** Every widget hidden via the settings menu — told plainly rather than left as a blank grid. */
function NoWidgetsSelectedNotice() {
  return (
    <p className="text-sm text-[color:var(--text-secondary)] xl:col-span-2">
      No dashboard components are selected. Use the Widgets button above to choose which to show.
    </p>
  )
}
