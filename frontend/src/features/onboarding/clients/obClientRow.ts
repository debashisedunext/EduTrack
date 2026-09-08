import type { ObClient } from '@/api/generated/model/obClient'
import type { ObClientStatus } from '@/api/generated/model/obClientStatus'

import { ragLabel, ragVariant } from '../journey/clientDetail/journeyStrip'

export type ChipVariant = 'success' | 'warning' | 'danger' | 'info' | 'neutral'

export interface RowChip {
  label: string
  variant: ChipVariant
  /** The tooltip and the accessible description — why the chip says what it says. */
  hint: string
}

/**
 * B-108 · what OB-03 puts in the Health column.
 *
 * ## Three states in one column, and only one of them is a colour
 *
 * `ObRag` refuses to be six things at once, and this is the screen that made
 * the refusal necessary: the prototype's client chip merged on-track, at-risk,
 * breached, waiting, prerequisites-pending and live into one label. The field
 * is now three separate ones, so the *cell* is where they are reconciled —
 * once, here, rather than in the JSX of every surface that renders a client.
 *
 * The order is the order a reader needs them in:
 *
 * 1. **Gate locked wins.** A client behind its prerequisites has `rag: null`
 *    and no running clock, so there is genuinely nothing to colour, and the
 *    thing somebody can act on is the checklist rather than the health. §9
 *    calls this "Prerequisites pending" and that is the label.
 * 2. **Then the colour**, when the server sent one.
 * 3. **Then "Not started"** — an open gate and no colour, which is a client
 *    whose journeys are all complete or archived. Neutral, because a client
 *    with nothing running is not a problem.
 *
 * `LIVE` is deliberately *not* folded in. It is `ObClientStatus`, it has its
 * own column, and a client can be live and amber on a journey bought after
 * go-live — collapsing the two would hide the second.
 */
export function healthChip(client: Pick<ObClient, 'gateStatus' | 'rag'>): RowChip {
  if (client.gateStatus === 'LOCKED') {
    return {
      label: 'Prerequisites pending',
      variant: 'info',
      hint: 'No journey has started — the prerequisite gate is still closed, so nothing is running to colour.',
    }
  }
  const rag = client.rag ?? null
  if (rag == null) {
    return {
      label: 'Not started',
      variant: 'neutral',
      hint: 'The gate is open and no service is running — every journey is finished or archived.',
    }
  }
  return {
    label: ragLabel(rag),
    variant: ragVariant(rag),
    hint: 'Worst health across this client’s open journeys.',
  }
}

const STATUS_CHIPS: Record<ObClientStatus, { label: string; variant: ChipVariant }> = {
  ONBOARDING: { label: 'Onboarding', variant: 'neutral' },
  LIVE: { label: 'Live', variant: 'success' },
  // Amber rather than red: a hold is a decision somebody recorded, not a
  // failure. `DROPPED` is red because it is the end of the relationship.
  ON_HOLD: { label: 'On hold', variant: 'warning' },
  DROPPED: { label: 'Dropped', variant: 'danger' },
}

export function statusChip(status: ObClientStatus): { label: string; variant: ChipVariant } {
  return STATUS_CHIPS[status] ?? { label: status, variant: 'neutral' }
}

/**
 * "2 / 5" — journeys complete over journeys bought.
 *
 * `journeysComplete` is optional in the contract, and absent is not zero: a row
 * that never carried the field would otherwise report every client as having
 * finished nothing. It is defaulted rather than hidden because the server does
 * send it and the alternative is a column that is blank on every row if one
 * deployment is behind — but the default is stated here so the next reader does
 * not have to guess which of the two it is.
 */
export function journeyProgress(client: Pick<ObClient, 'journeyCount' | 'journeysComplete'>): {
  label: string
  hint: string
} {
  const total = client.journeyCount ?? 0
  const done = client.journeysComplete ?? 0
  if (total === 0) {
    return {
      label: 'No products',
      hint: 'This client has bought nothing yet, so there is no journey to run.',
    }
  }
  return {
    label: `${done} / ${total}`,
    hint: `${done} of ${total} ${total === 1 ? 'journey' : 'journeys'} complete.`,
  }
}

/**
 * The products column, truncated with a count rather than wrapped.
 *
 * A client with nine products would otherwise make one row four lines tall and
 * push every other row off the first screen, which is the cost the grid is
 * paying to show a detail the detail page shows properly.
 */
export function productSummary(
  client: Pick<ObClient, 'products'>,
  visible = 2,
): { shown: string[]; overflow: number; title: string } {
  const names = (client.products ?? []).map((p) => p.name)
  return {
    shown: names.slice(0, visible),
    overflow: Math.max(0, names.length - visible),
    title: names.join(', '),
  }
}
