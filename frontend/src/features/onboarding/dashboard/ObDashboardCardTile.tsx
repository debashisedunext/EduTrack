import { Info } from 'lucide-react'

import type { ObDashboardCard } from '@/api/generated/model'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'

import { cardAccessibleName, cardLook, describeDelta } from './obDashboardCards'

const TONE_ACCENT: Record<'neutral' | 'warning' | 'danger', string> = {
  neutral: 'var(--primary)',
  warning: 'var(--warning)',
  danger: 'var(--danger)',
}

export interface ObDashboardCardTileProps {
  card: ObDashboardCard
  /** B-127 wires the S-06 slide-over here. Absent until then; see the note below. */
  onOpen?: (card: ObDashboardCard) => void
}

/**
 * B-121 · one card on the OB-02 board.
 *
 * <h2>A button, and not a link — where `KpiCard` is the reverse</h2>
 *
 * Stream A's ticketing card is an anchor, deliberately, because §S-05 says
 * "every card deep-links to a pre-filtered ticket list" and there *is* such a
 * list to link to. Plan §9 asks for something different here: "every card
 * clicks open a **right slide-over** listing the matching clients". There is no
 * URL behind that — the rows mix services and prerequisites, which A-118
 * records as the whole reason the slide-over is its own read rather than a
 * `drillDown` query string — so an anchor would be an anchor to nowhere, and
 * open-in-new-tab on it would land the user on a blank page.
 *
 * A `<button>` is what a control that opens a panel in place actually is, and
 * it keeps the three things `KpiCard`'s note is about — keyboard reach, an
 * announced role, and a real focus ring.
 *
 * **Until B-127 lands there is no handler**, and a tile with no `onOpen`
 * renders as a plain region rather than as a button that does nothing when
 * pressed. A dead control is worse than none: it teaches the user the board is
 * broken.
 *
 * <h2>Three states, and the two that are not a number</h2>
 *
 * `unavailableReason` — the caller's scope has no table that can answer this
 * card. The sentence replaces the number rather than sitting under a zero,
 * because a zero renders as "nothing is overdue", which is a factual claim and
 * a false one. A-056 settled this for the ticketing widgets a Developer's
 * summary table cannot serve.
 *
 * `countIsUpperBound` — the number is real but may overstate, because the
 * summary table stores client counts per product and a client who bought two
 * products is counted in both. Shown as `≈` with the reason on hover and in the
 * accessible name; see this feature's README for why the exact figure is not
 * recoverable without a schema change.
 */
export function ObDashboardCardTile({ card, onOpen }: ObDashboardCardTileProps) {
  const { label, tone, caption } = cardLook(card.key)
  const delta = describeDelta(card.deltaFromYesterday)
  const unavailable = card.unavailableReason
  const interactive = Boolean(onOpen) && !unavailable

  const body = (
    <>
      {/*
        `tracking-[.06em]` and a tight leading, not the old `.08em`: the row is
        now six equal shares of the width rather than six fixed 190px tiles, so
        a label has less room on a narrow screen and the widest of them —
        "This week's deadlines" — has to wrap inside the tile rather than set
        its width.
      */}
      <span className="flex items-center gap-1.5 text-[11px] font-semibold uppercase leading-tight tracking-[.06em] text-[color:var(--text-secondary)]">
        <span className="min-w-0 break-words">{label}</span>
        {card.countIsUpperBound && (
          <Info aria-hidden="true" className="h-3.5 w-3.5 shrink-0 normal-case" />
        )}
      </span>

      {unavailable ? (
        <p className="text-sm leading-snug text-[color:var(--text-secondary)]">{unavailable}</p>
      ) : (
        <>
          <span className="flex items-baseline gap-2">
            <span
              className="text-[28px] font-[650] leading-9 tabular-nums tracking-[-.01em] text-[color:var(--text-primary)]"
              style={tone === 'neutral' ? undefined : { color: TONE_ACCENT[tone] }}
            >
              {/*
                The `≈` is aria-hidden and the same caveat is spelled out in the
                accessible name. A screen reader announcing "almost equal to
                twelve" says nothing about *why*, which is the only part that
                helps.
              */}
              {card.countIsUpperBound && <span aria-hidden="true">≈</span>}
              {card.count.toLocaleString()}
            </span>
            {delta && (
              <span className="text-xs tabular-nums text-[color:var(--text-secondary)]">
                <span aria-hidden="true">
                  {delta.arrow} {delta.magnitude.toLocaleString()}
                </span>
              </span>
            )}
          </span>
          {caption && <span className="text-xs text-[color:var(--text-secondary)]">{caption}</span>}
        </>
      )}
    </>
  )

  // `min-w-0` is what lets the tile shrink below its content's intrinsic width
  // now that the row is equal shares rather than fixed 190px tracks; `px-4`
  // buys back the eight pixels that costs a narrow tile.
  const shell =
    'rounded-card border border-[color:var(--border)] bg-[color:var(--bg-surface)] shadow-sm px-4 py-4 ' +
    'flex h-full w-full min-w-0 flex-col gap-1 text-left min-h-[6.5rem]'

  if (!interactive) {
    return (
      <div
        className={shell}
        // Not a button, so the name has to be carried by the group itself or a
        // screen reader reads the label and the number as two loose strings.
        role="group"
        aria-label={cardAccessibleName(card)}
        title={card.countIsUpperBound ? UPPER_BOUND_HINT : undefined}
      >
        {body}
      </div>
    )
  }

  return (
    <button
      type="button"
      onClick={() => onOpen?.(card)}
      className={cn(
        shell,
        'transition-shadow hover:shadow-lg',
        'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2',
        'focus-visible:outline-[color:var(--primary)]',
      )}
      aria-label={`${cardAccessibleName(card)} Open the matching clients.`}
      title={card.countIsUpperBound ? UPPER_BOUND_HINT : undefined}
    >
      {body}
    </button>
  )
}

/**
 * The hover text behind `≈`. Plain enough to be useful to somebody who has
 * never read the schema, and honest that the figure is a ceiling rather than an
 * estimate — "approximately" would suggest it could be under.
 */
const UPPER_BOUND_HINT =
  'At most this many. Clients are counted once per product they bought, so a client with ' +
  'several products is counted several times. Choose a product to see the exact figure.'

/** Shown while the first request is in flight — same footprint, so nothing reflows on arrival. */
export function ObDashboardCardTileSkeleton() {
  return (
    <div className="rounded-card border border-[color:var(--border)] bg-[color:var(--bg-surface)] p-4 flex min-h-[6.5rem] flex-col gap-2">
      <Skeleton className="h-4 w-28" />
      <Skeleton className="h-8 w-16" />
    </div>
  )
}
