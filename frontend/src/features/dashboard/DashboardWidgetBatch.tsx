import * as React from 'react'

import { useGetDashboardWidgets } from '@/api/generated/dashboard/dashboard'
import type { GetDashboardWidgetParams } from '@/api/generated/model'

import type { WidgetKey } from './WidgetFrame'
import { BatchContext, type BatchValue, type WidgetPayload } from './widgetBatchContext'

/**
 * A-073 · one request for the whole first paint, instead of one per widget.
 *
 * ## This reverses a decision A-056 made deliberately, so here is the argument
 *
 * `DashboardWidgets` used to say, in as many words: *"Six requests, not one.
 * Each widget fetches itself... A single combined endpoint would have made the
 * whole block one all-or-nothing request whose slowest query set the latency
 * for every chart in it."* That was reasonable on the evidence available when
 * it was written. A-073 measured it at 50,000 tickets, and three of its four
 * supporting points do not survive the measurement:
 *
 * - **"the slowest query sets the latency for every chart"** — there is no
 *   slowest query. All ten widgets measured within 12 ms of one another
 *   (31–43 ms), because a widget's own work is ~7 ms of a ~20 ms call and the
 *   rest is fixed per-request cost. Ten times that fixed cost *was* the
 *   latency: p95 for first paint was 4.33 s against a 1.5 s budget.
 *
 * - **"each carries its own ETag, so an unchanged chart costs a 304"** — the
 *   granularity was illusory. Every per-widget ETag is a hash of the same
 *   `computed_at`, so all ten change together, every time, by construction. One
 *   validator over the set says exactly the same thing.
 *
 * - **"a role that cannot be answered for one widget still gets the rest"** —
 *   preserved exactly. The server renders each widget independently and returns
 *   `unavailableReason` per widget; batching changes nothing about that, and
 *   the permission matrix pins the two routes to the same answer.
 *
 * The fourth point is a real cost and is not dismissed: **one widget's query
 * throwing now fails the whole batch**, where before it would have left the
 * others standing. Accepted rather than solved — all ten widgets read the same
 * three summary tables through the same repository, so a failure in one is
 * overwhelmingly likely to be a failure in all, and swallowing a per-widget
 * exception to keep the others alive would turn a server fault into a silently
 * incomplete dashboard, which is the worse of the two failures.
 *
 * ## The single-widget route is still there and still used
 *
 * `useWidget` falls back to `GET /dashboard/widget/{key}` whenever it is used
 * outside a provider. Not dead code kept for politeness: re-fetching one tile
 * after a drill-down is genuinely one request, and its finer ETag is the right
 * validator for it. Batching is a first-paint optimisation, not a replacement.
 */
export function DashboardWidgetBatch({
  keys,
  params,
  children,
}: {
  keys: readonly WidgetKey[]
  params: GetDashboardWidgetParams
  children: React.ReactNode
}) {
  // The joined string is the query's identity, so it is what the memo depends
  // on. Depending on `keys` itself would refetch the entire dashboard on every
  // render, because a fresh array literal is a new reference each time — and
  // `[keys.join(',')]` as an inline dependency is the same thing written where
  // the linter cannot check it. Widget keys never contain a comma, so the
  // round-trip through split is exact.
  const keySignature = keys.join(',')
  // Re-narrowed to WidgetKey[], which `split` cannot know: it returns string[],
  // and the generated params want the contract's key enum. Sound because the
  // signature was built from a WidgetKey[] two lines up and widget keys contain
  // no comma, so the round-trip is exact rather than merely plausible.
  //
  // The empty case is handled separately: `''.split(',')` is `['']`, not `[]`
  // — a one-element array holding an empty string, which is not a widget key
  // and would reach the server as one. Every caller passed a non-empty
  // literal until the dashboard settings menu made "everything hidden" a
  // real, reachable state.
  const keyList = React.useMemo(
    () => (keySignature === '' ? [] : (keySignature.split(',') as WidgetKey[])),
    [keySignature],
  )

  const { data, isPending, isError } = useGetDashboardWidgets(
    { keys: keyList, ...params },
    // Nothing to ask the server for — firing a request naming zero widgets
    // would be a round trip whose answer is always an empty list.
    { query: { enabled: keyList.length > 0 } },
  )

  const value = React.useMemo<BatchValue>(() => {
    const byKey = new Map<string, WidgetPayload>()
    for (const widget of data?.data ?? []) {
      if (widget?.key) {
        byKey.set(widget.key, widget)
      }
    }
    return { byKey, isPending, isError }
  }, [data, isPending, isError])

  return <BatchContext.Provider value={value}>{children}</BatchContext.Provider>
}
