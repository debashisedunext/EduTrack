import * as React from 'react'

import { useGetDashboardWidget } from '@/api/generated/dashboard/dashboard'
import type { GetDashboardWidgetParams } from '@/api/generated/model'

import type { WidgetKey } from './WidgetFrame'

/**
 * A-073 · the context a batched dashboard puts its widgets in, and the hook a
 * `WidgetFrame` reads them out of.
 *
 * Split from `DashboardWidgetBatch.tsx` because `react-refresh/only-export-components`
 * refuses a module that exports both a component and something else — a file
 * mixing the two breaks fast refresh in development, and the lint job runs with
 * `--max-warnings 0`. The provider lives next door; everything that is not a
 * component lives here.
 *
 * The argument for batching at all is in `DashboardWidgetBatch.tsx`.
 */

export interface WidgetPayload {
  series?: { name?: string; points?: { x?: unknown; y?: number; drillDown?: string | null }[] }[]
  unavailableReason?: string | null
}

export interface BatchValue {
  byKey: Map<string, WidgetPayload>
  isPending: boolean
  isError: boolean
}

export const BatchContext = React.createContext<BatchValue | null>(null)

/**
 * One widget's payload — from the batch when there is one, from its own request
 * otherwise.
 *
 * Both hooks are called unconditionally; the single-widget query is simply
 * disabled inside a provider. Calling one *or* the other would break the rules
 * of hooks the first time a `WidgetFrame` moved into or out of a batch.
 *
 * **A key the batch did not return is not an error.** The server drops keys it
 * does not implement rather than failing the set, so an absent key means "not
 * served" and renders as the empty state — the same outcome the single route's
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
