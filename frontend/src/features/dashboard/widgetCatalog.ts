import type { WidgetKey } from './WidgetFrame'

/**
 * Every widget the dashboard can draw, in the order `DashboardWidgets`
 * renders the organisation variant — the order the settings menu lists them
 * in, so a reader can match a checkbox to its position on the grid below.
 *
 * `label` is the short catalogue name, not the widget's on-screen title —
 * several titles carry role- or variant-specific phrasing (`WidgetFrame`'s
 * "My velocity" for a delivery role) that would be a confusing entry in a
 * settings list shown to everyone alike.
 */
export const WIDGET_CATALOG: { key: WidgetKey; label: string }[] = [
  { key: 'type-donut', label: 'Task type distribution' },
  { key: 'daily-stacked', label: 'Daily task' },
  { key: 'velocity', label: 'Resource velocity' },
  { key: 'resource-load', label: 'Resource-wise load' },
  { key: 'priority-bar', label: 'Priority split' },
  { key: 'aging-buckets', label: 'Ticket aging' },
  { key: 'calendar-heatmap', label: 'Date-wise activity' },
  { key: 'sla-gauge', label: 'SLA compliance' },
  { key: 'project-treemap', label: 'Project distribution' },
  { key: 'client-volume', label: 'Client-wise ticket raise' },
  { key: 'stage-funnel', label: 'Stage funnel' },
  { key: 'rework', label: 'Rework' },
  { key: 'stage-duration', label: 'Average hours per stage visit' },
  { key: 'handoff-latency', label: 'Time waiting between stages' },
]

export const ALL_WIDGET_KEYS: readonly WidgetKey[] = WIDGET_CATALOG.map((w) => w.key)
