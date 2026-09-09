import { useNavigate } from 'react-router-dom'
import { format, parseISO } from 'date-fns'

import { useListObDashboardCardItems } from '@/api/generated/onboarding/onboarding'
import type { ObDashboardCardKey, ObDashboardItem } from '@/api/generated/model'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import {
  SlideOver,
  SlideOverBody,
  SlideOverContent,
  SlideOverHeader,
  SlideOverTitle,
} from '@/components/ui/slide-over'

import { cardLook } from './obDashboardCards'

/** One page. `listObDashboardCardItems` is cursor-paginated behind this — see the class note. */
const PAGE_LIMIT = 50

export interface ObDashboardDrillPanelProps {
  /** Which card's rows to show, or `null`/`undefined` to keep the panel closed. */
  cardKey: ObDashboardCardKey | null | undefined
  onClose: () => void
  /**
   * Overrides the card's own label — B-128 opens this same panel from a
   * workload-grid cell and wants "Priya's clients", not "Ongoing projects".
   * Defaults to the card's label from {@link cardLook}.
   */
  title?: string
  productId?: number
  /**
   * Narrows to one implementor, matching owner **or** backup owner — the
   * contract's own reading, and how B-128's workload grid is meant to open
   * this same panel. See {@link ObDashboardDrillPanelProps.cardKey}'s note:
   * the grid still has to name a card, because the route the panel reads
   * from requires one.
   */
  ownerUserId?: number
}

/**
 * B-127 · the S-06 right slide-over behind one OB-02 card — plan §9: "every
 * card clicks open a right slide-over listing the matching clients with
 * product, item, owner, due and status, each row opening the client."
 *
 * <h2>A generic panel, not one built per card</h2>
 *
 * `listObDashboardCardItems` is one route for all seven cards — the contract's
 * own point, restated on the frontend: `ObDashboardItem` is already a union of
 * services and prerequisites, so nothing here needs to know which of the two a
 * row is beyond what field it shows blank. `cardKey` is the only thing that
 * changes between a click on "This week's deadlines" and a click on
 * "Client escalations".
 *
 * <h2>Exported for B-128 to reuse verbatim</h2>
 *
 * The workload grid's cells are meant to open this exact panel filtered by
 * `ownerUserId`, per the contract's note on that parameter ("how the workload
 * grid's cells open into this same slide-over"). Nothing here assumes the
 * caller is a card tile — `cardKey`, `title`, `productId` and `ownerUserId` are
 * all plain props, so a grid cell can drive it with a fixed `cardKey` (the one
 * that best matches the bucket it clicked) and its own `ownerUserId` and title.
 *
 * <h2>One page, not an accumulating "load more"</h2>
 *
 * The route is cursor-paginated — `ObDashboardCardItemsIT` proves the keyset
 * round-trips a page boundary — but this panel only ever reads the first page,
 * `DrillDownPanel`'s own choice one module over: a slide-over is a look, not a
 * workspace, and a caller with more than {@link PAGE_LIMIT} rows is told so
 * rather than handed a second request to make. Paging further is a screen this
 * module does not have yet (this README's own "no grids" note).
 *
 * <h2>No ETag, unlike the board behind it</h2>
 *
 * The contract declares none for this route — a bounded live query has no
 * cheaper validator than the query itself — so this always refetches rather
 * than ever reading `304`.
 */
export function ObDashboardDrillPanel({
  cardKey,
  onClose,
  title,
  productId,
  ownerUserId,
}: ObDashboardDrillPanelProps) {
  const navigate = useNavigate()
  const open = cardKey != null

  const { data, isPending, isError } = useListObDashboardCardItems(
    // A real value is required even while closed; `enabled` is what stops the
    // request firing — `DrillDownPanel`'s own reasoning for the identical
    // shape one module over. Never read when `open` is false.
    cardKey ?? 'ongoing-projects',
    { productId, ownerUserId, limit: PAGE_LIMIT },
    { query: { enabled: open } },
  )

  const rows = data?.data ?? []
  const hasMore = data?.meta?.hasMore ?? false
  const heading = title ?? (cardKey ? cardLook(cardKey).label : '')

  return (
    <SlideOver open={open} onOpenChange={(next) => !next && onClose()}>
      <SlideOverContent className="max-w-2xl" aria-describedby="ob-dashboard-drill-description">
        <SlideOverHeader>
          <SlideOverTitle>{heading}</SlideOverTitle>
          <p id="ob-dashboard-drill-description" className="text-xs text-content-muted">
            {data?.meta?.computedAt
              ? `The count above is as of ${new Date(data.meta.computedAt).toLocaleString(undefined, {
                  dateStyle: 'medium',
                  timeStyle: 'short',
                })}; these rows are live.`
              : 'These rows are live, read when the panel opened.'}
          </p>
        </SlideOverHeader>

        <SlideOverBody>
          {isPending ? (
            <div className="flex flex-col gap-2">
              {Array.from({ length: 6 }, (_, i) => (
                <Skeleton key={i} className="h-12 w-full" />
              ))}
            </div>
          ) : isError ? (
            <EmptyState
              title="This list could not be loaded"
              description="The card's own count is unaffected. Try opening it again."
            />
          ) : rows.length === 0 ? (
            <EmptyState
              title="Nothing matches this card right now"
              description="The board's count can be up to a few minutes ahead of this list — B-120's refresh interval."
            />
          ) : (
            <table className="w-full text-left text-sm">
              <caption className="sr-only">{heading}</caption>
              <thead className="text-xs text-content-muted">
                <tr>
                  <th scope="col" className="py-2 font-medium">Client</th>
                  <th scope="col" className="py-2 font-medium">Product</th>
                  <th scope="col" className="py-2 font-medium">Item</th>
                  <th scope="col" className="py-2 font-medium">Owner</th>
                  <th scope="col" className="py-2 font-medium whitespace-nowrap">Due</th>
                  <th scope="col" className="py-2 font-medium">Status</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((item) => (
                  <DrillRow
                    key={`${item.itemType}-${item.itemId}`}
                    item={item}
                    onOpenClient={() => {
                      onClose()
                      navigate(`/onboarding/clients/${item.obClientId}`)
                    }}
                  />
                ))}
              </tbody>
            </table>
          )}

          {hasMore && (
            <p className="mt-3 text-xs text-content-muted">
              Showing the first {PAGE_LIMIT}. Narrow with a product filter to see the rest.
            </p>
          )}
        </SlideOverBody>
      </SlideOverContent>
    </SlideOver>
  )
}

function DrillRow({ item, onOpenClient }: { item: ObDashboardItem; onOpenClient: () => void }) {
  return (
    <tr className="border-t border-border align-top">
      <td className="py-2 pr-3 whitespace-nowrap">
        <button
          type="button"
          onClick={onOpenClient}
          className="rounded-sm text-primary underline-offset-2 hover:underline
                     focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2
                     focus-visible:outline-primary"
        >
          {item.obClientName}
        </button>
      </td>
      <td className="py-2 pr-3 text-content-muted">{item.product?.name ?? '—'}</td>
      <td className="py-2 pr-3">{item.title}</td>
      <td className="py-2 pr-3 text-content-muted">{item.owner?.displayName ?? '—'}</td>
      <td className="py-2 pr-3 whitespace-nowrap text-content-muted">
        {item.dueAt ? (
          <time dateTime={item.dueAt}>{format(parseISO(item.dueAt), 'd MMM yyyy')}</time>
        ) : (
          '—'
        )}
      </td>
      <td className="py-2 whitespace-nowrap">
        <span className="flex items-center gap-1.5">
          {item.status}
          {item.isOverdue && <Chip variant="danger">Overdue</Chip>}
        </span>
      </td>
    </tr>
  )
}
