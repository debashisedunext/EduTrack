import * as React from 'react'

import { useDrillDownStore } from '../drillDownStore'

/**
 * A-056 · §S-05's rule, applied to chart segments.
 *
 * > "Every card and every chart segment is clickable and deep-links to a
 * > pre-filtered ticket list."
 *
 * The target is always the server's string — see `WidgetService` — so the
 * filter that produced the number and the filter the list applies are the same
 * one. A-055 made this argument for the cards and it holds harder here: a donut
 * has eleven segments, and eleven client-side filter reconstructions is eleven
 * chances to disagree with the arc that was clicked.
 *
 * <h2>Not every segment has a target, and that is not a bug</h2>
 *
 * `drillDown` is null on the aging buckets, because the ticket list has no age
 * filter to express them. A segment with no target does nothing on click rather
 * than navigating somewhere approximate — a chart that opens a list
 * contradicting the segment you clicked is worse than one that does not open.
 *
 * <h2>Keyboard reach</h2>
 *
 * Recharts segments are `<path>` elements: not focusable, not announced, and
 * unreachable without a mouse. The chart drawing is therefore `aria-hidden`
 * (see `WidgetFrame`) and the accessible path to the same destinations is the
 * legend beneath it, which is built from real `<button>`s. Click-through on the
 * drawing is a convenience for pointer users, never the only way in.
 *
 * <h2>The segment's own number goes with it (D-064)</h2>
 *
 * The panel heads itself with the figure that was clicked, and a chart has one
 * per segment — the donut arc says 24, the legend beside it says 24, and the
 * panel must not then say something else. Passed from the datum rather than
 * counted from the rows the panel fetches, for the same reason the cards do it:
 * the contract calls `meta.totalCount` "present only where a count is cheap,
 * never computed live over tickets".
 *
 * Undefined is honest and prints nothing. A segment with no figure — an aging
 * bucket, which has no drill-down at all — must not have one invented for it.
 */
export function useDrillDown(title?: string) {
  const open = useDrillDownStore((s) => s.open)

  return React.useCallback(
    (drillDown: string | null | undefined, label?: string, count?: number | null) => {
      if (!drillDown) {
        return
      }
      // A-061 · the panel, not a navigation. §S-06 asks for the filtered grid
      // beside the chart rather than instead of it — checking *which* eleven
      // tickets is a glance, and a full page load costs the reader the place
      // they were scanning. "Open full list" inside the panel is the
      // navigation, for when the glance turns out to be a destination.
      open(drillDown, label ?? title ?? 'Filtered tickets', count ?? null)
    },
    [open, title],
  )
}
