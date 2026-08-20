import { useQueryClient } from '@tanstack/react-query'

import { getListTicketsQueryKey } from '@/api/generated/tickets/tickets'
import { ticketTopic } from '@/realtime/destinations'
import { useRealtime } from '@/realtime/useRealtime'

/**
 * D-058 · the ribbon advances while you are looking at it.
 *
 * `TransitionService.advance` raises `TicketStageAdvanced`, and
 * `RibbonLiveBroadcaster` turns it into `stage.changed` on
 * `/topic/ticket.{id}` **after the commit** (C-045). This is the half that
 * listens. §17 item 12 is the failure it closes on the detail page — the
 * QA lead watching a ticket they just handed to a developer should see it
 * land, not learn about it from a refresh they had no reason to perform.
 *
 * ## The frame is a nudge; the server is the answer
 *
 * `{event, ticketId, fromStage, toStage}` carries no ribbon, no assignee and
 * no history row, and none of it is written into the cache. Every query for
 * this ticket is invalidated and React Query refetches the ones that are
 * mounted. That is `StageQueueBroadcaster`'s stated convention — "the client
 * refetches, so the database is the only answer and a missed frame leaves a
 * client stale rather than wrong" — and it matters more here than on a queue:
 * a handoff moves stage, assignee, status, the ribbon, the journey grid, the
 * history tab and the effort ledger at once, and patching six caches from a
 * four-field frame is how a page comes to disagree with itself.
 *
 * ## Why a predicate rather than a list of query keys
 *
 * Every generated key for this ticket begins with the request path —
 * `/tickets/{code}/full`, `/journey`, `/history`, `/effort-logs`,
 * `/attachments`, `/ribbon`. Matching on that prefix invalidates the tabs
 * that exist today **and the ones added after this file was written**. An
 * enumerated list would be correct on the day it was written and quietly
 * short by one every time a tab is added — the tab would simply never
 * update, with nothing to point at.
 *
 * The prefix ends in `/` deliberately: `/tickets/CRM-26-4` must not match
 * `/tickets/CRM-26-47/full`.
 *
 * ## Nothing subscribes until the numeric id is known
 *
 * The route carries the **ticket code**; the topic is keyed by the numeric
 * id, which arrives with the detail response. `useRealtime` treats `null` as
 * "do not subscribe" for exactly this — subscribing to a guessed destination
 * opens a room nobody publishes to and receives nothing, forever and
 * silently (`destinations.ts`' own reason for existing).
 *
 * @param ticketCode the `:ticketId` route param — a code, not an id
 * @param ticketId   the numeric id from the detail payload; `undefined` until it loads
 */
export function useLiveRibbon(ticketCode: string, ticketId: number | undefined): void {
  const queryClient = useQueryClient()

  useRealtime(ticketId ? ticketTopic(ticketId) : null, (payload) => {
    if ((payload as { event?: string } | null)?.event !== 'stage.changed') {
      // The ticket topic carries more than this task's event — comments and
      // typing share the room (`destinations.ts`). Refetching the whole
      // ticket on a keystroke is not what any of them asked for.
      return
    }

    const prefix = `/tickets/${ticketCode}/`
    void queryClient.invalidateQueries({
      predicate: (query) => {
        const first = query.queryKey[0]
        return typeof first === 'string' && first.startsWith(prefix)
      },
    })

    // Stage, assignee and status changed, and the list and My Tasks render
    // all three — `useHandoffMutation`'s identical invalidation, for the
    // case where somebody else performed the handoff.
    void queryClient.invalidateQueries({ queryKey: getListTicketsQueryKey() })
  })
}
