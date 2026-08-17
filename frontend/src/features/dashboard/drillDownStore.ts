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
  open: (drillDown: string, title: string) => void
  close: () => void
}

export const useDrillDownStore = create<DrillDownStore>((set) => ({
  drillDown: null,
  title: '',
  open: (drillDown, title) => set({ drillDown, title }),
  // The title is left standing on close. Radix animates the panel out over
  // ~200ms and clearing it here would blank the heading mid-flight, which reads
  // as a glitch rather than as a dismissal.
  close: () => set({ drillDown: null }),
}))
