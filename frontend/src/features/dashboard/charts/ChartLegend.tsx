/**
 * A-056 · the keyboard-reachable half of every chart.
 *
 * Recharts draws segments as `<path>` elements. They are not focusable, not
 * announced, and cannot be reached without a mouse — so the drawing is
 * `aria-hidden` in `WidgetFrame` and this legend carries the interaction. Each
 * entry is a real `<button>`, which means tab order, an announced role, and
 * Enter/Space for free; the same three things `KpiCard` chose an anchor for.
 *
 * A segment with no `drillDown` renders as plain text rather than a disabled
 * button — a button that cannot be pressed still takes a tab stop and still
 * announces itself as a control, which is a worse experience than a label that
 * was never a control. The aging buckets are the case: the ticket list has no
 * age filter, so those four have nowhere to go.
 */
export interface ChartLegendEntry {
  label: string
  colour: string
  drillDown: string | null
  /** Shown after the label — the figure the segment represents. */
  value?: number
}

export interface ChartLegendProps {
  entries: ChartLegendEntry[]
  /**
   * A-061 · the entry's own label rides along, so §S-06's panel can head itself
   * with what was actually clicked — "Critical" rather than the widget's name.
   * The drill-down string alone cannot supply that: it is a filter, and
   * reverse-engineering a heading from query parameters is how a panel comes to
   * describe something subtly different from the segment that opened it.
   */
  onSelect: (drillDown: string | null, label?: string) => void
  /** Names what the entries are, for the group's accessible name. */
  label: string
}

export function ChartLegend({ entries, onSelect, label }: ChartLegendProps) {
  return (
    <ul aria-label={label} className="flex flex-wrap gap-x-4 gap-y-1 text-xs">
      {entries.map((entry) => (
        <li key={entry.label} className="flex items-center gap-1.5">
          <span
            aria-hidden="true"
            className="inline-block h-2.5 w-2.5 shrink-0 rounded-sm"
            style={{ backgroundColor: entry.colour }}
          />
          {entry.drillDown ? (
            <button
              type="button"
              onClick={() => onSelect(entry.drillDown, entry.label)}
              className="text-[color:var(--text-secondary)] underline-offset-2 hover:underline
                         focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2
                         focus-visible:outline-[color:var(--primary)] rounded-sm"
              aria-label={
                entry.value === undefined
                  ? `${entry.label}. Open the filtered ticket list.`
                  : `${entry.label}: ${entry.value}. Open the filtered ticket list.`
              }
            >
              {entry.label}
              {entry.value !== undefined && (
                <span className="ml-1 tabular-nums text-[color:var(--text-primary)]">{entry.value}</span>
              )}
            </button>
          ) : (
            <span className="text-[color:var(--text-secondary)]">
              {entry.label}
              {entry.value !== undefined && (
                <span className="ml-1 tabular-nums text-[color:var(--text-primary)]">{entry.value}</span>
              )}
            </span>
          )}
        </li>
      ))}
    </ul>
  )
}
