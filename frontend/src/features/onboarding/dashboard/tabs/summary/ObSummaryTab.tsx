import type { ObDashboardCard } from '@/api/generated/model'
import { EmptyState } from '@/components/ui/empty-state'

import { ObDashboardCardTile, ObDashboardCardTileSkeleton } from '../../ObDashboardCardTile'
import { ObDashboardRagBoard } from '../../ObDashboardRagBoard'

/**
 * B-121/B-128, tabbed · the Summary tab — the seven counters and the RAG
 * board, exactly what the whole page used to draw before it grew four tabs.
 *
 * Neither section fetches anything of its own beyond what {@link
 * ObDashboardRagBoard} already did: the seven cards are `ObDashboardPage`'s
 * one `GET /onboarding/dashboard/summary` request, passed down as props
 * rather than re-fetched here, so switching back to this tab never re-asks
 * for a number the page already has.
 */
export function ObSummaryTab({
  cards,
  isPending,
  isError,
  onOpen,
}: {
  cards: ObDashboardCard[]
  isPending: boolean
  isError: boolean
  onOpen: (card: ObDashboardCard) => void
}) {
  return (
    <div className="flex flex-col gap-5">
      {isError ? (
        <EmptyState
          title="The board could not be loaded"
          description="Refresh to try again. If it keeps failing, the onboarding module may not be enabled for your account."
        />
      ) : (
        <div
          /*
            A list, not a bare grid of divs. Seven tiles with no grouping are
            seven unrelated announcements; naming the group is what tells a
            screen-reader user how many there are and that they belong together.
          */
          role="list"
          aria-label="Onboarding summary"
          /*
            Fixed-width tracks (`auto-fill`, not `auto-fit … 1fr`) — every card
            is 190px whatever the row it lands in. `1fr` stretches whichever row
            has the fewest cards to fill the leftover width, which is exactly
            what made "Live"/"At risk" (a two-card second row) balloon wider
            than the five cards above them.
          */
          className="grid grid-cols-[repeat(auto-fill,190px)] items-stretch gap-4"
        >
          {isPending
            ? Array.from({ length: 7 }, (_, index) => (
                <div role="listitem" key={index}>
                  <ObDashboardCardTileSkeleton />
                </div>
              ))
            : cards.map((card) => (
                <div role="listitem" key={card.key}>
                  <ObDashboardCardTile card={card} onOpen={onOpen} />
                </div>
              ))}
        </div>
      )}

      <ObDashboardRagBoard />
    </div>
  )
}
