import type { GetDashboardWidgetParams } from '@/api/generated/model'

import { WidgetFrame } from './WidgetFrame'
import { AgingBuckets } from './charts/AgingBuckets'
import { CalendarHeatmap } from './charts/CalendarHeatmap'
import { DailyStackedArea } from './charts/DailyStackedArea'
import { PriorityBar } from './charts/PriorityBar'
import { ProjectTreemap } from './charts/ProjectTreemap'
import { ResourceLoadBar } from './charts/ResourceLoadBar'
import { SlaGauge } from './charts/SlaGauge'
import { TypeDonut } from './charts/TypeDonut'
import { VelocityLines } from './charts/VelocityLines'

/**
 * A-056 · widgets 7–12 of §S-05, in the two-column layout the blueprint draws.
 *
 * <h2>Six requests, not one</h2>
 *
 * Each widget fetches itself. That is deliberate and it is what
 * `GET /dashboard/widget/{widgetKey}` was shaped for: one widget failing leaves
 * the other five standing, each carries its own `ETag` so an unchanged chart
 * costs a 304, and a role that cannot be answered for one widget still gets the
 * rest. A single combined endpoint would have made the whole block one
 * all-or-nothing request whose slowest query set the latency for every chart in
 * it.
 *
 * <h2>The layout leaves room for A-057</h2>
 *
 * Two columns at `xl`, one below — the pairing §S-05 draws, and the same grid
 * A-054's shell was laid out to take without rearranging. Widgets 13–15 append
 * to this list; nothing here needs to move for them.
 */
export function DashboardWidgets({ params }: { params: GetDashboardWidgetParams }) {
  return (
    <div className="grid gap-3 xl:grid-cols-2">
      <WidgetFrame
        widgetKey="type-donut"
        title="Task type distribution"
        categoryLabel="Task type"
        params={params}
      >
        {(series) => <TypeDonut series={series} />}
      </WidgetFrame>

      <WidgetFrame
        widgetKey="daily-stacked"
        title="Daily task status"
        categoryLabel="Date"
        params={params}
      >
        {(series) => <DailyStackedArea series={series} />}
      </WidgetFrame>

      <WidgetFrame
        widgetKey="velocity"
        title="Resource velocity (tickets closed per week)"
        categoryLabel="Week beginning"
        params={params}
      >
        {(series) => <VelocityLines series={series} />}
      </WidgetFrame>

      <WidgetFrame
        widgetKey="resource-load"
        title="Resource-wise load"
        categoryLabel="Resource"
        params={params}
      >
        {(series) => <ResourceLoadBar series={series} />}
      </WidgetFrame>

      <WidgetFrame
        widgetKey="priority-bar"
        title="Priority split"
        categoryLabel="Priority"
        params={params}
      >
        {(series) => <PriorityBar series={series} />}
      </WidgetFrame>

      <WidgetFrame
        widgetKey="aging-buckets"
        title="Ticket aging"
        categoryLabel="Age range"
        params={params}
      >
        {(series) => <AgingBuckets series={series} />}
      </WidgetFrame>

      {/* A-057 · widgets 13–15. Appended, with nothing above rearranged —
          which is what A-054's grid and A-056's frame were shaped for. */}
      <WidgetFrame
        widgetKey="calendar-heatmap"
        title="Date-wise activity"
        categoryLabel="Date"
        params={params}
        wide
      >
        {(series) => <CalendarHeatmap series={series} />}
      </WidgetFrame>

      <WidgetFrame
        widgetKey="sla-gauge"
        title="SLA compliance"
        categoryLabel="Outcome"
        params={params}
      >
        {(series) => <SlaGauge series={series} />}
      </WidgetFrame>

      <WidgetFrame
        widgetKey="project-treemap"
        title="Project-wise distribution"
        categoryLabel="Project"
        params={params}
      >
        {(series) => <ProjectTreemap series={series} />}
      </WidgetFrame>
    </div>
  )
}
