import type { ObClient } from '@/api/generated/model/obClient'
import type { ObClientStatus } from '@/api/generated/model/obClientStatus'
import type { ObDelayedProject } from '@/api/generated/model/obDelayedProject'
import type { ObProductRef } from '@/api/generated/model/obProductRef'

import { ragVariant } from '../journey/clientDetail/journeyStrip'

export type ChipVariant = 'success' | 'warning' | 'danger' | 'info' | 'neutral'

export interface RowChip {
  /**
   * The mockup's glyph — ● ✓ ⚠ ⏸ 🔒 — rendered `aria-hidden` beside the label,
   * never inside it, so a screen reader hears the words and a test can match
   * the label alone.
   */
  glyph: string
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
 * `LIVE` *is* folded in, which reverses this file's earlier position: the
 * OB-03 mockup (`docs/prototype/onboarding.html`, `clientRag`) has no Status
 * column at all — a live client's health chip **is** "● Live", and it wins
 * over any colour a post-go-live journey may carry. The colour is still on
 * the detail page; the list shows the mockup's six-chip vocabulary.
 */
export function healthChip(client: Pick<ObClient, 'gateStatus' | 'rag' | 'status'>): RowChip {
  if (client.status === 'LIVE') {
    return {
      glyph: '●',
      label: 'Live',
      variant: 'success',
      hint: 'This client has gone live.',
    }
  }
  if (client.gateStatus === 'LOCKED') {
    return {
      glyph: '🔒',
      label: 'Prerequisites pending',
      variant: 'neutral',
      hint: 'No journey has started — the prerequisite gate is still closed, so nothing is running to colour.',
    }
  }
  const rag = client.rag ?? null
  if (rag == null) {
    return {
      glyph: '⏸',
      label: 'Not started',
      variant: 'neutral',
      hint: 'The gate is open and no service is running — every journey is finished or archived.',
    }
  }
  if (rag === 'RED') {
    // The mockup's copy: RED is one chip for breached *and* blocked, because
    // both are "our clock ran out or cannot run" — the detail page separates
    // them.
    return {
      glyph: '⚠',
      label: 'Breached / blocked',
      variant: 'danger',
      hint: 'Worst health across this client’s open journeys.',
    }
  }
  return {
    glyph: rag === 'GREEN' ? '✓' : '⚠',
    label: rag === 'GREEN' ? 'On track' : 'At risk',
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
  /** Every journey done — the mockup's "Journeys complete" state for the Current step column. */
  complete: boolean
} {
  const total = client.journeyCount ?? 0
  const done = client.journeysComplete ?? 0
  if (total === 0) {
    return {
      label: 'No products',
      hint: 'This client has bought nothing yet, so there is no journey to run.',
      complete: false,
    }
  }
  if (done >= total) {
    return {
      label: 'Journeys complete',
      hint: `All ${total} ${total === 1 ? 'journey is' : 'journeys are'} complete.`,
      complete: true,
    }
  }
  return {
    label: `${done} / ${total} complete`,
    hint: `${done} of ${total} ${total === 1 ? 'journey' : 'journeys'} complete.`,
    complete: false,
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

/**
 * One Projects-grid row: a client **and one of its products**.
 *
 * A school that bought ERP and CRM is two rows — "ABC School · ERP" then
 * "ABC School · CRM" — rather than one row whose product cell reads
 * "ERP, CRM".
 *
 * ## Why the split happens here and not on the server
 *
 * The row shape this describes is `ObJourneySummary`, and the contract has a
 * cross-client list that returns exactly it: `GET /onboarding/journeys`, whose
 * own description reads "a client who bought three products is three rows
 * here". **That collection is not implemented** — `ObJourneyReadController`
 * maps `GET /{journeyId}` and nothing else, so the route exists in
 * `openapi.yaml` and in MSW and 404s against the real API. Splitting
 * `listObClients`' `products[]` is what can be built against the backend that
 * exists today, and `isClientHead` records the one cost.
 *
 * ## `isClientHead` — the row the client-level columns are actually true of
 *
 * Only the product cell is a fact about *this product*. Health is the worst
 * colour across every open journey, the journey counter counts all of them,
 * and `startedAt`/`currentStep.dueAt` belong to the **primary** journey alone.
 * Those columns therefore repeat down a client's rows describing something
 * wider than the row they sit on.
 *
 * `toProductRows` orders the products so the primary journey's product comes
 * first and flags that row, so a caller that wants to render those columns
 * once — or mark the repeats — has the information to. The grid currently
 * repeats them, which is the lesser of the two evils while a blank cell reads
 * as missing data. The **client name** is repeated for the same reason and a
 * stronger one: a continuation row with a blank Client cell does not say whose
 * product it is, which is the one thing every row has to say.
 */
export interface ObClientProductRow {
  /** Stable and unique across the page — a client with no products still needs one. */
  key: string
  client: ObClient
  /** Null only for a client that has bought nothing; the grid still renders one row for it. */
  product: ObProductRef | null
  /** True on the client's first row — the primary journey's product, where one is running. */
  isClientHead: boolean
}

/**
 * Flattens a page of clients into one row per product, primary journey first.
 *
 * A client with no products still yields one row, with `product: null`. It is a
 * boarded client, and dropping it would make the grid disagree with its own
 * "N boarded" caption and with a pagination footer that counts clients.
 */
export function toProductRows(clients: readonly ObClient[]): ObClientProductRow[] {
  return clients.flatMap((client): ObClientProductRow[] => {
    const products = client.products ?? []
    if (products.length === 0) {
      return [{ key: `${client.id}:none`, client, product: null, isClientHead: true }]
    }

    // The primary journey's product first, so the client-level columns lead
    // with the journey they describe. `currentStep` is null on a gate-locked or
    // finished client — then server order stands, and the first product is the
    // best anchor available.
    const primaryId = client.currentStep?.product?.id
    const ordered =
      primaryId == null
        ? products
        : [...products].sort((a, b) => Number(b.id === primaryId) - Number(a.id === primaryId))

    return ordered.map((product, i) => ({
      key: `${client.id}:${product.id}`,
      client,
      product,
      isClientHead: i === 0,
    }))
  })
}

/**
 * Why a client-level date column has nothing to print — words, never a bare
 * dash.
 *
 * The Projects grid's rule is that no cell is left blank: a dash tells a
 * reader that something is missing without telling them whether it is missing
 * because nothing has started, because everything finished, or because the
 * read failed. `startedAt` and `currentStep.dueAt` are null under the cases
 * `ObClient` itself documents, and those cases have names.
 */
export function missingDateReason(
  client: Pick<ObClient, 'gateStatus' | 'journeyCount' | 'journeysComplete' | 'status'>,
): { label: string; hint: string } {
  if (client.gateStatus === 'LOCKED') {
    return {
      label: 'Not started',
      hint: 'The prerequisite gate is still closed — no service has ever activated.',
    }
  }
  if (journeyProgress(client).complete) {
    return { label: 'Complete', hint: 'Every journey is finished, so no service is due.' }
  }
  return {
    label: 'Not scheduled',
    hint: 'The primary journey has no service running — it is finished, or held behind another journey.',
  }
}

/** One journey per (client, product), as far as this grid is concerned. */
export function delayKey(clientId: number, productId: number): string {
  return `${clientId}:${productId}`
}

/**
 * `GET /onboarding/dashboard/delayed-projects`, indexed the way the Projects
 * grid reads it.
 *
 * A product can carry **more than one journey** — a product publishes several
 * Module Services and each runs its own (`ObJourneySummary.serviceName`) — and
 * this grid has one row per product, so two delayed journeys collapse into one
 * cell. The **worst** one wins: a row that reported the milder of two delays
 * would understate the client to the person deciding who to chase.
 */
export function indexDelayedProjects(
  rows: readonly ObDelayedProject[],
): Map<string, ObDelayedProject> {
  const worst = new Map<string, ObDelayedProject>()
  for (const row of rows) {
    const key = delayKey(row.obClientId, row.product.id)
    const held = worst.get(key)
    if (held == null || row.delayedByDays > held.delayedByDays) worst.set(key, row)
  }
  return worst
}

/** Whether the delay read has landed. A column cannot say "on time" before it has. */
export type DelayReadState = 'ready' | 'loading' | 'error'

/** The Delayed and Responsible cells of one Projects row. */
export interface DelayCells {
  /**
   * The chip carries the working-day count in its own label — "Delayed · 4
   * working days". There is no separate "Delayed by" column, because on a grid
   * where most rows are not late that column is mostly "On schedule" and
   * "Not started" repeated down the page; the number belongs on the rows that
   * have one.
   */
  delayed: RowChip
  /** A person's name where one is known, and the reason it is not where it is not. */
  responsible: { label: string; hint: string; named: boolean }
}

/**
 * The two columns that say whether this journey is behind and who holds it.
 *
 * ## They come from a different read, and only one of them is complete
 *
 * `listObClients` carries no step owner and no delay count — and the
 * working-day figure could not be subtracted here anyway, because it has to go
 * through the working calendar (CLAUDE.md): a journey due Friday and
 * unfinished on Monday is one working day late, not three. Both facts do exist
 * on `GET /onboarding/dashboard/delayed-projects`, which returns one row per
 * delayed journey with `delayedByDays` already calendar-corrected and the
 * owner (or backup owner) of the step that made it late.
 *
 * That read covers **delayed journeys only**. So a delayed row names a real
 * person and a real count, and an on-time row can only say that nothing is
 * late — the owner of a healthy journey is on no endpoint the backend
 * implements today. `GET /onboarding/journeys` is the read that would carry it
 * (`ObJourneySummary.owner`) and its collection is still unimplemented, the
 * same gap `ObClientProductRow` records. Said in the cell rather than papered
 * over: "Unknown" with a hint beats a blank, which is this grid's rule.
 *
 * The order of the decisions is what keeps them honest. A locked or finished
 * journey is not "on time" — it is not running at all — and both of those the
 * client row knows for itself, without the delay read having landed.
 */
export function delayCells(
  row: Pick<ObClientProductRow, 'client'>,
  delayed: ObDelayedProject | undefined,
  state: DelayReadState = 'ready',
): DelayCells {
  const { client } = row

  if (delayed != null) {
    const days = delayed.delayedByDays
    const count = `${days} working ${days === 1 ? 'day' : 'days'}`
    const stage = delayed.currentStep?.name
    return {
      delayed: {
        glyph: '⚠',
        label: `Delayed · ${count}`,
        // Five working days is a week of one person's time — the same line
        // `ObDelayedProjectsGrid` draws between its two chip weights.
        variant: days >= 5 ? 'danger' : 'warning',
        hint: `${count} past the expected completion, through the working calendar — weekends and org holidays are not counted${stage ? ` · currently on “${stage}”` : ''}.`,
      },
      responsible: delayed.responsible
        ? {
            label: delayed.responsible.displayName,
            hint: 'Owner of the overdue service, or its backup where the owner is unresolved.',
            named: true,
          }
        : {
            label: 'Unassigned',
            hint: 'The overdue service has neither an owner nor a backup — which is itself worth chasing.',
            named: false,
          },
    }
  }

  if (client.gateStatus === 'LOCKED') {
    const hint =
      'The prerequisite gate is still closed — nothing is running to be late, and nobody holds it yet.'
    return {
      delayed: { glyph: '🔒', label: 'Not started', variant: 'neutral', hint },
      responsible: { label: 'Not started', hint, named: false },
    }
  }

  if (journeyProgress(client).complete) {
    const hint = 'Every journey is finished — there is nothing left to be late or to hold.'
    return {
      delayed: { glyph: '✓', label: 'Complete', variant: 'success', hint },
      responsible: { label: 'Complete', hint, named: false },
    }
  }

  if (state !== 'ready') {
    const loading = state === 'loading'
    const label = loading ? 'Checking…' : 'Unavailable'
    const hint = loading
      ? 'Still reading the delayed-projects list.'
      : 'The delayed-projects read failed, so this row cannot be called on time either.'
    return {
      delayed: { glyph: '·', label, variant: 'neutral', hint },
      responsible: { label, hint, named: false },
    }
  }

  return {
    delayed: {
      glyph: '✓',
      label: 'On time',
      variant: 'success',
      hint: 'No service on this journey is past its due date.',
    },
    responsible: {
      label: 'Unknown',
      hint: 'Only a delayed journey names who holds it on this read. Open the client to see the current service’s owner.',
      named: false,
    },
  }
}
