import * as React from 'react'

import { useListTicketHistory } from '@/api/generated/tickets/tickets'
import { ApiError } from '@/api/http'
import type { HistoryEntry } from '@/api/generated/model/historyEntry'

export interface UseTicketHistoryOptions {
  ticketId: string
  /**
   * Narrows to one cycle, matching the page's `?cycle=` — same contract as
   * `useTicketComments`. `undefined` (the default, current-cycle view) asks
   * for every cycle at once, which is the point of a *cycle-grouped* tab; a
   * past, sealed cycle opened through the summary panel's cycle links narrows
   * to just that cycle's own read-only journey.
   */
  cycle?: number
  /**
   * Defaults to `true`. `TicketDetailPage` passes `activeTab === 'history'` —
   * unlike the comment box, nothing outside this tab needs the stream, so
   * fetching it before the tab is ever opened would be a request for a page
   * most visitors never scroll to.
   */
  enabled?: boolean
}

export interface UseTicketHistoryResult {
  entries: HistoryEntry[]
  isLoading: boolean
  loadError: string | null
  includeComments: boolean
  setIncludeComments: (value: boolean) => void
  hasMore: boolean
  isLoadingMore: boolean
  loadMore: () => void
}

const PAGE_LIMIT = 200

/**
 * C-059 · the History tab's one query, plus the `include=comments` toggle
 * blueprint §4B.5 asks for: "comments are interleaved into the History tab
 * alongside field changes and handoffs, in one chronological stream".
 *
 * ## Why this fetches rather than reading the `/full` payload
 *
 * `detail.history` exists on `GET /tickets/{id}/full` already, and
 * `TicketSummaryPanel`'s `assignedBy` reads it today — but that projection is
 * cycle-scoped to whichever cycle the page is viewing (A-052's `?cycle=`
 * narrows both journals) and carries no `include=comments` toggle at all.
 * `useTicketComments`'s own note on the identical question applies here
 * unchanged: a ticket's full history has no cap, the contract gives
 * `listTicketHistory` its own cursor and limit for exactly that reason, and
 * `/full` has no way to express either.
 *
 * ## `includeComments` is local, not a URL param
 *
 * Unlike `?cycle=`, whether comments are folded into the stream is a reading
 * preference rather than a fact about which ticket state is on screen — a
 * colleague pasting a link to this ticket should not inherit "I had the
 * comments toggle on", the way they should inherit "I was looking at cycle 1".
 */
export function useTicketHistory({ ticketId, cycle, enabled = true }: UseTicketHistoryOptions): UseTicketHistoryResult {
  const [includeComments, setIncludeComments] = React.useState(false)
  const [cursor, setCursor] = React.useState<string | undefined>(undefined)
  const [accumulated, setAccumulated] = React.useState<HistoryEntry[]>([])

  // A new cycle, ticket or toggle state starts the stream over — the
  // accumulated pages belong to the previous query and must not linger under
  // a new one, or "load more" would silently mix two different filters.
  React.useEffect(() => {
    setCursor(undefined)
    setAccumulated([])
  }, [ticketId, cycle, includeComments])

  const { data, isPending, isFetching, isError, error } = useListTicketHistory(
    ticketId,
    {
      cycle,
      include: includeComments ? ['comments'] : undefined,
      cursor,
      limit: PAGE_LIMIT,
    },
    { query: { enabled: enabled && ticketId.length > 0, retry: false } },
  )

  React.useEffect(() => {
    if (!data) return
    setAccumulated((prev) => (cursor == null ? (data.data ?? []) : [...prev, ...(data.data ?? [])]))
    // `cursor` is intentionally not a dependency — it is what changed to
    // *cause* this fetch, and including it would refire this effect a second
    // time for the same response once `setCursor` below updates it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data])

  const loadMore = React.useCallback(() => {
    const next = data?.meta?.nextCursor
    if (next) setCursor(next)
  }, [data?.meta?.nextCursor])

  return {
    entries: accumulated,
    isLoading: isPending,
    loadError: isError ? messageFor(error, 'This ticket’s history could not be loaded.') : null,
    includeComments,
    setIncludeComments,
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
