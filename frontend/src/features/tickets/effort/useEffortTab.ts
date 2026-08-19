import * as React from 'react'

import { useListEffortLogs } from '@/api/generated/tickets/tickets'
import { ApiError } from '@/api/http'
import type { EffortLog } from '@/api/generated/model/effortLog'

export interface UseEffortTabOptions {
  ticketId: string
  /**
   * Matches the page's `?cycle=`, the same contract `useTicketHistory` and
   * `useTicketComments` already follow. `undefined` (the default, current-cycle
   * view) asks for every cycle at once, which is the point of a cycle-grouped
   * tab; landing on an earlier, sealed cycle through `cycleEffortPath` narrows
   * to just that cycle's own read-only log.
   */
  cycle?: number
  /**
   * Defaults to `true`. `TicketDetailPage` passes `activeTab === 'effort'` —
   * nothing outside this tab needs the full log, and `detail.effortLogs` above
   * is cycle-scoped for the summary panel's own at-a-glance purpose.
   */
  enabled?: boolean
}

export interface UseEffortTabResult {
  entries: EffortLog[]
  isLoading: boolean
  loadError: string | null
  hasMore: boolean
  isLoadingMore: boolean
  loadMore: () => void
}

const PAGE_LIMIT = 200

/**
 * C-061 · the Effort tab's one query, blueprint §7: "Effort tab — every log
 * line: date, resource, hours, description, cycle no. Sum per cycle + grand
 * total."
 *
 * ## Why this fetches rather than reading the `/full` payload
 *
 * `detail.effortLogs` exists on `GET /tickets/{id}/full` already, and the
 * summary panel's "Logged" row reads `detail.cycles` for the same reason —
 * but `effortLogs` itself is cycle-scoped to whichever cycle the page is
 * viewing (A-052's `?cycle=`), and grouping every cycle's lines is the whole
 * point of this tab. `useTicketHistory`'s identical note applies unchanged.
 *
 * ## Totals are not read from this hook
 *
 * `EffortLogListResponse.meta.cycleTotalHrs` is always the ticket's *current*
 * cycle, independent of any `cycle` filter on the request — the right figure
 * for "how much has landed on the ticket as it stands today", and the wrong
 * one for an arbitrary cycle group this tab renders. `EffortTab` sums each
 * group from `Cycle.totalEffortHrs` via `ticketSummary.ts`'s `cycleEffortHrs`
 * and `totalEffortHrs` instead — the same materialised, C-041-maintained
 * figures the summary panel already trusts, and correct regardless of how
 * many pages of raw lines have been fetched.
 */
export function useEffortTab({ ticketId, cycle, enabled = true }: UseEffortTabOptions): UseEffortTabResult {
  const [cursor, setCursor] = React.useState<string | undefined>(undefined)
  const [accumulated, setAccumulated] = React.useState<EffortLog[]>([])

  // A new cycle or ticket starts the log over — the accumulated pages belong
  // to the previous query and must not linger under a new one.
  React.useEffect(() => {
    setCursor(undefined)
    setAccumulated([])
  }, [ticketId, cycle])

  const { data, isPending, isFetching, isError, error } = useListEffortLogs(
    ticketId,
    { cycle, cursor, limit: PAGE_LIMIT },
    { query: { enabled: enabled && ticketId.length > 0, retry: false } },
  )

  React.useEffect(() => {
    if (!data) return
    setAccumulated((prev) => (cursor == null ? (data.data ?? []) : [...prev, ...(data.data ?? [])]))
    // `cursor` is intentionally not a dependency — see `useTicketHistory`'s
    // identical note.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data])

  const loadMore = React.useCallback(() => {
    const next = data?.meta?.nextCursor
    if (next) setCursor(next)
  }, [data?.meta?.nextCursor])

  return {
    entries: accumulated,
    isLoading: isPending,
    loadError: isError ? messageFor(error, 'This ticket’s effort log could not be loaded.') : null,
    hasMore: Boolean(data?.meta?.hasMore),
    isLoadingMore: isFetching && cursor != null,
    loadMore,
  }
}

function messageFor(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    return error.problem.detail ?? error.problem.title ?? fallback
  }
  return fallback
}
