import * as React from 'react'

import {
  useGetDashboardWidget,
  useGetDashboardWidgets,
} from '@/api/generated/dashboard/dashboard'
import type { GetDashboardWidgetParams } from '@/api/generated/model'

import type { WidgetKey } from './WidgetFrame'

/**
 * A-073 · one request for the whole first paint, instead of one per widget.
 *
 * ## This reverses a decision A-056 made deliberately, so here is the argument
 *
 * `DashboardWidgets` used to say, in as many words: *"Six requests, not one.
 * Each widget fetches itself... A single combined endpoint would have made the
 * whole block one all-or-nothing request whose slowest query set the latency
 * for every chart in it."* That was a reasonable call on the evidence available
 * when it was written. A-073 measured it at 50,000 tickets, and three of its
 * four supporting points do not survive the measurement:
 *
 * - **"the slowest query sets the latency for every chart"** — there is no
 *   slowest query. All ten widgets measured within 12 ms of one another
 *   (31–43 ms), because a widget's own work is ~7 ms of a ~20 ms call and the
 *   rest is fixed per-request cost. Ten times a fixed cost was the actual
 *   latency, and the p95 for first paint was 943 ms against a 500 ms budget.
 *
 * - **"each carries its own ETag, so an unchanged chart costs a 304"** — the
 *   granularity was illusory. Every per-widget ETag is a hash of the same
 *   `computed_at`, so all ten change together, every time, by construction. One
 *   validator over the set expresses exactly the same thing.
 *
 * - **"a role that cannot be answered for one widget still gets the rest"** —
 *   preserved exactly. The server renders each widget independently and returns
 *   `unavailableReason` per widget; nothing about batching changes that, and
 *   the permission matrix pins the two routes to the same answer.
 *
 * The fourth point is a real cost and is not dismissed: **one widget's query
 * throwing now fails the whole batch**, where before it would have left the
 * others standing. That is accepted rather than solved, because all ten widgets
 * read the same three summary tables through the same repository — a failure in
 * one is overwhelmingly likely to be a failure in all — and swallowing a
 * per-widget exception to keep the others alive would turn a server fault into
 * a silently incomplete dashboard, which is the worse of the two failures.
 *
 * ## The single-widget route is still there and still used
 *
 * `useWidget` falls back to `GET /dashboard/widget/{key}` whenever it is used
 * outside a provider. That is not dead code kept for politeness: re-fetching
 * one tile after a drill-down is genuinely one request, and its finer ETag is
 * the right validator for it. Batching is a first-paint optimisation, not a
 * replacement.
 */

interface BatchValue {
  byKey: Map<string, WidgetPayload>
  isPending: boolean
  isError: boolean
}

interface WidgetPayload {
  series?: { name?: string; points?: { x?: unknown; y?: number; drillDown?: string | null }[] }[]
  unavailableReason?: string | null
}

const BatchContext = React.createContext<BatchValue | null>(null)

/**
 * Fetches `keys` in one request and makes them available to any `WidgetFrame`
 * below.
 *
 * `keys` must list every widget rendered inside, and only those. A key rendered
 * but not listed falls back to its own request, which is correct but silently
 * gives up the point of this component; a key listed but not rendered costs a
 * query for a chart nobody sees.
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
  // Joined into a stable string so the query key does not change identity on
  // every render the way a fresh array would, which would refetch the whole
  // dashboard each time the page re-rendered for any reason at all.
  const keyList = React.useMemo(() => [...keys], [keys.join(',')])

  const { data, isPending, isError } = useGetDashboardWidgets({
    keys: keyList,
    ...params,
  })

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

/**
 * One widget's payload, from the batch when there is one and from its own
 * request otherwise.
 *
 * Both hooks are called unconditionally — the single-widget query is simply
 * disabled inside a provider. Calling one or the other would break the rules of
 * hooks the first time a `WidgetFrame` moved into or out of a batch.
 *
 * **A key the batch did not return is not an error.** The server drops keys it
 * does not implement rather than failing the set, so an absent key means "not
 * served", which renders as the empty state — the same thing the single route's
 * 404 produced before.
 */
export function useWidget(widgetKey: WidgetKey, params: GetDashboardWidgetParams) {
  const batch = React.useContext(BatchContext)

  const single = useGetDashboardWidget(widgetKey, params, {
    query: { enabled: batch === null },
  })

  if (batch !== null) {
    return {
      data: batch.byKey.get(widgetKey),
      isPending: batch.isPending,
      isError: batch.isError,
    }
  }

  return {
    data: single.data?.data,
    isPending: single.isPending,
    isError: single.isError,
  }
}
