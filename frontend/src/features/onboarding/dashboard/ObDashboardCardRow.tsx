import type { ObDashboardCard } from '@/api/generated/model'
import { EmptyState } from '@/components/ui/empty-state'

import { ObDashboardCardTile, ObDashboardCardTileSkeleton } from './ObDashboardCardTile'
import { VISIBLE_CARD_COUNT, visibleCards } from './obDashboardCards'

/**
 * B-121/B-128 · the six counters, as the board's first row.
 *
 * <h2>Above the tabs, not inside one</h2>
 *
 * The row used to be the Summary tab's opening section, which made the page's
 * headline numbers invisible from the other three tabs — a manager reading
 * Delayed projects could not see how many clients were overdue without
 * navigating away and back. They are the page's standing figures rather than
 * one tab's content, so they sit above the strip and stay on screen whichever
 * tab is open. The tabs below then divide the *detail*, which is the only
 * thing that actually differs between them.
 *
 * It costs nothing to keep them there: the counts are
 * `ObDashboardPage`'s single `GET /onboarding/dashboard/summary`, passed down
 * as props, so this component fetches nothing of its own.
 */
export function ObDashboardCardRow({
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
  const shown = visibleCards(cards)
  // The row is always one row, so the track count follows the number of tiles
  // actually drawn. A Tailwind class cannot carry a runtime count, and rounding
  // it to a fixed `grid-cols-6` would wrap the moment the server adds a card.
  const columns = Math.max(shown.length || VISIBLE_CARD_COUNT, 1)

  if (isError) {
    return (
      <EmptyState
        title="The board could not be loaded"
        description="Refresh to try again. If it keeps failing, the onboarding module may not be enabled for your account."
      />
    )
  }

  return (
    <div
      /*
        A list, not a bare grid of divs. Six tiles with no grouping are six
        unrelated announcements; naming the group is what tells a screen-reader
        user how many there are and that they belong together.
      */
      role="list"
      aria-label="Onboarding summary"
      /*
        One row, always — one track per tile, each an equal share of the width.
        The earlier `repeat(auto-fill, 190px)` fixed the tile width instead and
        let the row wrap, which put two cards on a second line on any screen
        narrower than about 1300px.

        `minmax(0, 1fr)` rather than `1fr`: a grid track's implicit minimum is
        `auto`, which is the widest thing inside it, so a long label would
        refuse to shrink and push the row wider than its container.
      */
      className="grid items-stretch gap-3"
      style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }}
    >
      {isPending
        ? Array.from({ length: VISIBLE_CARD_COUNT }, (_, index) => (
            <div role="listitem" key={index}>
              <ObDashboardCardTileSkeleton />
            </div>
          ))
        : shown.map((card) => (
            <div role="listitem" key={card.key}>
              <ObDashboardCardTile card={card} onOpen={onOpen} />
            </div>
          ))}
    </div>
  )
}
