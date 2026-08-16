import { useNavigate } from 'react-router-dom'
import * as React from 'react'

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
 */
export function useDrillDown() {
  const navigate = useNavigate()

  return React.useCallback(
    (drillDown: string | null | undefined) => {
      if (!drillDown) {
        return
      }
      navigate(drillDown)
    },
    [navigate],
  )
}
