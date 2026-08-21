import type { GetDashboardWidgetParams } from '@/api/generated/model'

import { DashboardWidgetBatch } from './DashboardWidgetBatch'
import type { WidgetKey } from './WidgetFrame'
import { WidgetFrame } from './WidgetFrame'
import { useDashboardVariant } from './useDashboardVariant'
import { AgingBuckets } from './charts/AgingBuckets'
import { CalendarHeatmap } from './charts/CalendarHeatmap'
import { ClientVolumeBar } from './charts/ClientVolumeBar'
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
 *
 * <h2>A-062 · the developer variant is this list, shorter</h2>
 *
 * §S-05: the Developer's dashboard "shows only widgets 1–6, 9, 12". Cards 1–6
 * are the row above; 9 and 12 are the two rendered below when the variant is
 * `own-work`, and the other eight are not requested at all.
 *
 * Before this, a delivery role loaded all nine and got six panels of
 * "this breakdown is not kept per resource" — each an accurate sentence, and
 * together a screen that reads as broken. The sentences stay, because they are
 * right for a PM who has filtered to a resource and for anybody who reaches a
 * widget another way; what changes is that the developer dashboard no longer
 * asks eight questions it already knows have no answer.
 *
 * **Widgets 10 and 13 are omitted although they would answer.** A-057 gave the
 * heatmap a per-resource measure ("tickets you closed") and widget 10 renders a
 * delivery role their own single bar. Both are left out because §S-05 names the
 * subset with the word "only" — a deliberate omission, not an oversight, and a
 * one-line change here if the variant should grow.
 */
/**
 * A-073 · the keys each variant renders, listed once so the batch asks for
 * exactly what is drawn.
 *
 * These must stay in step with the `WidgetFrame`s below. A key rendered but not
 * listed still works — `useWidget` falls back to its own request — but silently
 * costs the extra round trip this batching exists to remove; a key listed but
 * not rendered costs a query for a chart nobody sees. `DashboardWidgetsBatchTest`
 * pins both directions so the drift is a failing test rather than a slow page.
 */
const OWN_WORK_KEYS = ['velocity', 'aging-buckets'] as const satisfies readonly WidgetKey[]

const FULL_KEYS = [
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
  // A-058 · widgets 16–19. Appended for A-057's reason and with the same
  // result: nothing above moves, and the two-column grid takes them as it was
  // shaped to.
  'stage-funnel',
  'rework',
  'stage-duration',
  'handoff-latency',
] as const satisfies readonly WidgetKey[]

export function DashboardWidgets({ params }: { params: GetDashboardWidgetParams }) {
  const variant = useDashboardVariant()

  if (variant === 'own-work') {
    return (
      <DashboardWidgetBatch keys={OWN_WORK_KEYS} params={params}>
        <div className="grid gap-3 xl:grid-cols-2">
          <WidgetFrame
            widgetKey="velocity"
            title="My velocity (tickets closed per week)"
            categoryLabel="Week beginning"
            params={params}
          >
            {(series) => <VelocityLines series={series} />}
          </WidgetFrame>

          <WidgetFrame
            widgetKey="aging-buckets"
            // Titled as theirs. The server scopes it to them either way, and a
            // bar chart headed "Ticket aging" beside five figures that are all
            // "mine" invites being read as the organisation's.
            title="My ticket aging"
            categoryLabel="Age range"
            params={params}
          >
            {(series) => <AgingBuckets series={series} />}
          </WidgetFrame>
        </div>
      </DashboardWidgetBatch>
    )
  }

  return (
    <DashboardWidgetBatch keys={FULL_KEYS} params={params}>
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

      {/* A-059 · widget 20. Titled "raised" rather than "volume": the bars
          count tickets the client submitted in the window, and a panel headed
          "Client-wise volume" beside the treemap's open-ticket tiles invites
          being read as the same measure. The one word is the difference
          between intake and backlog. */}
      <WidgetFrame
        widgetKey="client-volume"
        title="Client-wise tickets raised"
        categoryLabel="Client"
        params={params}
      >
        {(series) => <ClientVolumeBar series={series} />}
      </WidgetFrame>

      {/* A-058 · widgets 16–19, the four the Workflow Ribbon unlocks. Appended
          like A-057's and A-059's, with nothing above rearranged.

          Kept together and last, because they are the only block on this screen
          that can all be unavailable at once: on an organisation not yet using
          the ribbon every one of them answers with the same sentence, and four
          adjacent notices read as one honest gap rather than as four failures
          scattered through a working dashboard. */}
      <WidgetFrame
        widgetKey="stage-funnel"
        title="Stage funnel (where work is sitting)"
        categoryLabel="Stage"
        params={params}
      >
        {(series) => <StageFunnel series={series} />}
      </WidgetFrame>

      <WidgetFrame
        widgetKey="rework"
        // "Rework" rather than §7.9's "Rework / ping-pong tickets": the panel
        // states the ping-pong figure in its own words, and a title naming both
        // invites the two numbers to be read as a pair that adds up. They do
        // not — the second is inside the first.
        title="Rework"
        categoryLabel="Rework state"
        params={params}
      >
        {(series) => <ReworkPanel series={series} />}
      </WidgetFrame>

      <WidgetFrame
        widgetKey="stage-duration"
        // "per visit" and not "per ticket": a ticket reworked twice visits DEV
        // twice and contributes two stays to this average. The distinction is
        // the whole reason widget 17 sits beside it.
        title="Average hours per stage visit"
        categoryLabel="Stage"
        params={params}
      >
        {(series) => <StageDurationBar series={series} />}
      </WidgetFrame>

      <WidgetFrame
        widgetKey="handoff-latency"
        // "waiting" rather than "latency": §7.6's term is precise and means
        // nothing to a PM reading a dashboard, and what the line measures is
        // a ticket sitting between two teams with nobody working on it.
        title="Time waiting between stages"
        categoryLabel="Date"
        params={params}
        wide
      >
        {(series) => <HandoffLatencyLine series={series} />}
      </WidgetFrame>
    </div>
    </DashboardWidgetBatch>
  )
}
