import { create } from 'zustand'

/**
 * A-061 · which drill-down the S-06 panel is showing, if any.
 *
 * <p>A store rather than props, for the same reason the command palette has
 * one: the openers are scattered — six KPI cards, nine chart legends, and the
 * segments inside each drawing — and every one of them sits several components
 * below the page that owns the panel. Threading a callback through
 * `DashboardWidgets` → `WidgetFrame` → each chart → `ChartLegend` would put a
 * prop on four components that do not otherwise care.
 *
 * <p>Deliberately **not** in the URL, unlike the dashboard's filters. A filter
 * is worth sharing — "here is the dashboard I am looking at" — but a transient
 * panel is not something anyone means to send, and putting it in the URL would
 * make the browser's Back button close the modal instead of leaving the
 * dashboard, which is the behaviour people actually expect from Back.
 */
interface DrillDownStore {
  /** The server-built `/tickets?…` string, or null when the panel is closed. */
  drillDown: string | null
  /** What was clicked — the card or series name — for the panel's heading. */
  title: string
  /**
   * D-064 · the figure printed on the thing that was clicked, or null when
   * whatever opened the panel had no single number (a chart legend, say).
   *
   * <p><strong>Carried, never counted.</strong> The contract says `totalCount`
   * is "present only where a count is cheap, never computed live over tickets",
   * which is CLAUDE.md's no-live-`COUNT(*)`-behind-a-dashboard rule — so this
   * is the card's own figure, which already came from the pre-aggregated
   * summary tables, rather than a count of the rows the panel fetched.
   *
   * <p><strong>It will not always equal the number of rows below it, and that
   * is understood.</strong> "Total tasks created" is a flow — raised inside the
   * window — while "Pending / open" is a stock, open right now whenever raised;
   * the open card's drill-down applies the reported-date window, so its list is
   * the intersection. Debashis chose the card's figure on 18 Aug knowing that.
   * The panel prints the window immediately beneath, so the two read as
   * different questions rather than as a contradiction.
   */
  count: number | null
  open: (drillDown: string, title: string, count?: number | null) => void
  close: () => void
}

export const useDrillDownStore = create<DrillDownStore>((set) => ({
  drillDown: null,
  title: '',
  count: null,
  open: (drillDown, title, count = null) => set({ drillDown, title, count }),
  // The title is left standing on close. Radix animates the panel out over
  // ~200ms and clearing it here would blank the heading mid-flight, which reads
  // as a glitch rather than as a dismissal.
  close: () => set({ drillDown: null }),
}))
