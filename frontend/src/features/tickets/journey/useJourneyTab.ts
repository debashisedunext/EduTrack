import { useGetJourney } from '@/api/generated/journey/journey'
import { ApiError } from '@/api/http'
import type { JourneyRow } from '@/api/generated/model/journeyRow'

export interface UseJourneyTabOptions {
  ticketId: string
  /**
   * Matches the page's `?cycle=`, the same contract `useEffortTab` and
   * `useTicketHistory` already follow.
   *
   * Unlike those two, this one is **not** an all-cycles view when undefined.
   * §4A.4's grid is one cycle's journey — the ribbon above it is per cycle
   * (C-053), and a grid interleaving two cycles' iterations would make the
   * `It` column ambiguous, since iteration restarts at 1 on a reopen. The
   * server defaults to the current cycle when the param is absent.
   */
  cycle?: number
  /**
   * Defaults to `true`. `TicketDetailPage` passes `activeTab === 'journey'`,
   * the identical gate `useEffortTab` and `useAttachmentsTab` carry.
   */
  enabled?: boolean
}

export interface UseJourneyTabResult {
  rows: JourneyRow[]
  isLoading: boolean
  loadError: string | null
}

/**
 * C-055 · the Journey tab's one query — `GET /tickets/{id}/journey`.
 *
 * **Not derived from the `/full` payload, though it looks like it could be.**
 * `detail.effortLogs` and the ribbon are both already on the page, and summing
 * one against the other in the browser would produce a grid that looked right.
 * It would also be wrong after a reopen: the join has to match `cycleNo` as
 * well as stage and iteration, or cycle 1's effort double-counts into cycle 2
 * — a defect in the blueprint's own query, corrected in PLAN.md §3.4. That
 * correctness belongs in one place, server-side, where C-058 implements it and
 * A-068's reports read the same numbers.
 *
 * **No pagination, deliberately.** A journey is bounded by how many hops a
 * ticket made in one cycle — single digits for anything that is not pathological
 * — so a cursor here would be machinery for a page that cannot grow.
 */
export function useJourneyTab({ ticketId, cycle, enabled = true }: UseJourneyTabOptions): UseJourneyTabResult {
  const { data, isPending, isError, error } = useGetJourney(
    ticketId,
    cycle == null ? undefined : { cycle },
    { query: { enabled: enabled && ticketId.length > 0, retry: false } },
  )

  return {
    rows: data?.data?.rows ?? [],
    isLoading: enabled && ticketId.length > 0 && isPending,
    loadError: isError ? messageFor(error) : null,
  }
}

function messageFor(error: unknown): string {
  if (error instanceof ApiError) return error.message
  return 'The journey could not be loaded.'
}
