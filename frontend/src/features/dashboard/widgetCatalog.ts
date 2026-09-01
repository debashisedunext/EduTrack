import type { WidgetKey } from './WidgetFrame'

/**
 * Every widget the dashboard can draw, and everything about it that is not a
 * drawing: its catalogue name, its on-screen heading, what its x axis holds and
 * whether it spans both columns.
 *
 * <h2>Why this is one table rather than fourteen JSX blocks</h2>
 *
 * It used to be the latter — `DashboardWidgets` held fourteen literal
 * `WidgetFrame` elements in the order the blueprint draws them, and that order
 * *was* the source order of the file. Which is exactly what made it impossible
 * to let somebody arrange their own dashboard: a sequence written by hand can be
 * filtered — all the settings menu ever needed — but it cannot be permuted,
 * because there is no list to permute.
 *
 * So the metadata moved here and the grid became a `map` over an ordered array
 * of keys. `DashboardWidgets` still owns the drawings — one renderer per key,
 * beside the charts they call — and this file owns everything a reorder has to
 * carry with a widget when it moves.
 *
 * <h2>The order here is the default, not the layout</h2>
 *
 * This array's order is what a browser that has never been arranged renders, and
 * it is the blueprint's §S-05 order deliberately. Once somebody drags a widget,
 * `dashboardWidgetPreferencesStore` holds their order and this one is consulted
 * only for widgets that stored order has never heard of.
 *
 * `label` is the short catalogue name and not `title`: several titles carry
 * variant-specific phrasing (`ownWorkTitle` below) that would be a confusing
 * entry in a settings list shown to everyone alike.
 */
export interface WidgetDefinition {
  key: WidgetKey
  /** The settings menu's name for it. Short, and the same for every role. */
  label: string
  /** The heading on the organisation dashboard. */
  title: string
  /**
   * The heading when the own-work variant draws it, where that differs.
   *
   * Only two widgets need one, and both for the same reason: the server scopes
   * them to the reader either way, and a chart headed "Ticket aging" beside five
   * figures that are all "mine" invites being read as the organisation's.
   */
  ownWorkTitle?: string
  /** What the x axis holds, for the frame's visually-hidden data table. */
  categoryLabel: string
  /** Spans both grid columns — the wide time-series widgets. */
  wide?: boolean
}

export const WIDGET_CATALOG: WidgetDefinition[] = [
  {
    key: 'type-donut',
    label: 'Task type distribution',
    title: 'Task type distribution',
    categoryLabel: 'Task type',
  },
  {
    key: 'daily-stacked',
    label: 'Daily task',
    title: 'Daily task status',
    categoryLabel: 'Date',
  },
  {
    key: 'velocity',
    label: 'Resource velocity',
    title: 'Resource velocity (tickets closed per week)',
    ownWorkTitle: 'My velocity (tickets closed per week)',
    categoryLabel: 'Week beginning',
  },
  {
    key: 'resource-load',
    label: 'Resource-wise load',
    title: 'Resource-wise load',
    categoryLabel: 'Resource',
  },
  {
    key: 'priority-bar',
    label: 'Priority split',
    title: 'Priority split',
    categoryLabel: 'Priority',
  },
  {
    key: 'aging-buckets',
    label: 'Ticket aging',
    title: 'Ticket aging',
    ownWorkTitle: 'My ticket aging',
    categoryLabel: 'Age range',
  },
  {
    key: 'calendar-heatmap',
    label: 'Date-wise activity',
    title: 'Date-wise activity',
    categoryLabel: 'Date',
    wide: true,
  },
  {
    key: 'sla-gauge',
    label: 'SLA compliance',
    title: 'SLA compliance',
    categoryLabel: 'Outcome',
  },
  {
    key: 'project-treemap',
    label: 'Project distribution',
    title: 'Project-wise distribution',
    categoryLabel: 'Project',
  },
  {
    key: 'client-volume',
    label: 'Client-wise ticket raise',
    // A-059 · "raised" rather than "volume": the bars count tickets the client
    // submitted in the window, and a panel headed "Client-wise volume" beside
    // the treemap's open-ticket tiles invites being read as the same measure.
    // The one word is the difference between intake and backlog.
    title: 'Client-wise tickets raised',
    categoryLabel: 'Client',
  },
  {
    key: 'module-open',
    label: 'Module-wise open tickets',
    // "Open" rather than "total": the bars count outstanding work — the three
    // segments partition it — and RESOLVED-not-CLOSED is deliberately excluded.
    // A panel headed "Module-wise total" would invite being read as every
    // ticket ever raised against the module, which is a different chart.
    title: 'Module-wise open tickets',
    categoryLabel: 'Module',
  },
  {
    key: 'stage-funnel',
    label: 'Stage funnel',
    title: 'Stage funnel (where work is sitting)',
    categoryLabel: 'Stage',
  },
  {
    key: 'rework',
    label: 'Rework',
    // "Rework" rather than §7.9's "Rework / ping-pong tickets": the panel states
    // the ping-pong figure in its own words, and a title naming both invites the
    // two numbers to be read as a pair that adds up. They do not — the second is
    // inside the first.
    title: 'Rework',
    categoryLabel: 'Rework state',
  },
  {
    key: 'stage-duration',
    label: 'Average hours per stage visit',
    // "per visit" and not "per ticket": a ticket reworked twice visits DEV twice
    // and contributes two stays to this average. The distinction is the whole
    // reason widget 17 sits beside it.
    title: 'Average hours per stage visit',
    categoryLabel: 'Stage',
  },
  {
    key: 'handoff-latency',
    label: 'Time waiting between stages',
    // "waiting" rather than §7.6's "latency": the spec's term is precise and
    // means nothing to a PM reading a dashboard, and what the line measures is a
    // ticket sitting between two teams with nobody working on it.
    title: 'Time waiting between stages',
    categoryLabel: 'Date',
    wide: true,
  },
]

export const ALL_WIDGET_KEYS: readonly WidgetKey[] = WIDGET_CATALOG.map((w) => w.key)

const BY_KEY = new Map<WidgetKey, WidgetDefinition>(WIDGET_CATALOG.map((w) => [w.key, w]))

/**
 * The definition for a key, which every member of `WidgetKey` has.
 *
 * Throws rather than returning undefined: that union and this array are both
 * maintained by hand, and a key added to one and not the other is worth failing
 * a test over rather than rendering a frame with no heading and no data table.
 */
export function widgetDefinition(key: WidgetKey): WidgetDefinition {
  const definition = BY_KEY.get(key)
  if (!definition) {
    throw new Error(`no widget catalogue entry for '${key}'`)
  }
  return definition
}

/** The heading this variant gives a widget. */
export function widgetTitle(key: WidgetKey, ownWork: boolean): string {
  const definition = widgetDefinition(key)
  return ownWork && definition.ownWorkTitle ? definition.ownWorkTitle : definition.title
}
