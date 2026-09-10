import type { ObJourneyStrip, ObProductRef, ObRag, ObStepDot } from '@/api/generated/model'

import { journeyHold, tatUsage, type JourneyHold, type TatUsage } from './journeyStrip'

/**
 * OB-05's product cards — the fold from "every journey this client runs" to
 * "one card per product they bought", kept out of the components that draw it.
 *
 * <h2>Why a product is not a journey</h2>
 *
 * Plan §0 and §20 are explicit that a client is boarded through **one journey
 * per Module Service of every product they bought**, and since the multi-service
 * change a single product can publish several services at once — ERP runs
 * Standard SaaS Onboarding *and* Enterprise with a migration audit. So the
 * journeys a client has are not a list of purchases: two of them can be the
 * same purchase, and a reader asking "how is the ERP going?" has to add them up
 * in their head.
 *
 * That is what this file does instead, and the grouping key is the product id
 * rather than its name — a renamed product must not split into two cards, and
 * two products that happen to share a display string must not merge into one.
 *
 * <h2>Everything here is arithmetic on figures the server sent</h2>
 *
 * The same rule `journeyStrip.ts` opens with, one level up. `percentComplete`
 * and the TAT pair are re-derived across a product's journeys because **the
 * server publishes neither at product level** — there is no product-level read
 * to disagree with. What is *not* re-derived is anything the server already
 * answers per journey: `rag` is picked from among the journeys' own RAGs rather
 * than recomputed from steps, for §10's reason (C-114 computes it on the
 * working calendar against the org's amber threshold, and a second opinion
 * here would be wrong on exactly the days it matters).
 */
export interface ObProductGroup {
  product: ObProductRef
  /** This product's journeys, in the order the client document listed them —
   * the admin-ordered service sequence (plan §5.5). */
  journeys: readonly ObJourneyStrip[]
  /** Services across every journey of the product. "Service" is §0's word for
   * a journey's step; the journeys themselves are Module Services. */
  servicesTotal: number
  servicesSettled: number
  /**
   * Settled services over total, the identical formula
   * `ObClientService#percentComplete` applies per journey — applied to the
   * union of the product's steps rather than averaged across its journeys.
   *
   * The average would weight a two-service journey the same as a nine-service
   * one, so a product whose small service is finished and whose large one has
   * not started would read 50% complete when one task in fourteen is done.
   */
  percentComplete: number
  /** The worst RAG any of the product's journeys carries, or null when none of
   * them carries one — a locked or unstarted product has no colour, which is
   * `ObJourneyStrip.rag`'s own contract. */
  rag: ObRag | null
  /**
   * What holds the whole product back, or null the moment any one of its
   * journeys is free to run.
   *
   * Null-when-any-runs is the direction that cannot mislead: a card saying
   * "prerequisites pending" over a product whose second service is already in
   * flight would send a reader to clear a gate that is not stopping anything.
   */
  hold: JourneyHold
  /** Σ of the product's journey budgets against Σ of their consumption. Null
   * when no journey has a budget — `tatUsage`'s "0/0 is not 0% used". */
  tat: TatUsage | null
  /** Every service id under this product, for scoping the client's open
   * escalations to the card that owns them. */
  stepIds: ReadonlySet<number>
}

/** What `ob-signoff-journey-incomplete` counts as finished, and what
 * `JourneyAccordion.journeyComplete` already agrees with: a waived service is a
 * settled one. */
function isSettled(step: ObStepDot): boolean {
  return step.status === 'DONE' || step.status === 'SKIPPED'
}

const RAG_SEVERITY: Record<string, number> = { GREEN: 1, AMBER: 2, RED: 3 }

/** A journey from a fixture or an older server with no product joined still has
 * to land on a card somebody can click, so it lands on this one. Never the
 * preferred name — `JourneyAccordion` makes the same concession for the same
 * reason. */
const UNKNOWN_PRODUCT: ObProductRef = { id: 0, code: '', name: 'Product' }

export function groupJourneysByProduct(journeys: readonly ObJourneyStrip[]): ObProductGroup[] {
  // Insertion-ordered rather than sorted: the client document already returns
  // journeys "in the admin-ordered service sequence", and re-sorting by name
  // here would silently overrule the sequence OB-07's ↑/↓ controls exist to set.
  const buckets = new Map<number, ObJourneyStrip[]>()
  for (const journey of journeys) {
    const key = journey.product?.id ?? UNKNOWN_PRODUCT.id
    const bucket = buckets.get(key)
    if (bucket) bucket.push(journey)
    else buckets.set(key, [journey])
  }
  return [...buckets.values()].map(toGroup)
}

/** The one product a page is scoped to, or undefined when this client never
 * bought it — which is a 404 on the product route, not an empty page. */
export function findProductGroup(
  journeys: readonly ObJourneyStrip[],
  productId: number,
): ObProductGroup | undefined {
  return groupJourneysByProduct(journeys).find((group) => group.product.id === productId)
}

function toGroup(journeys: ObJourneyStrip[]): ObProductGroup {
  const steps = journeys.flatMap((journey) => journey.steps ?? [])
  const servicesTotal = steps.length
  const servicesSettled = steps.filter(isSettled).length

  const rag = journeys.reduce<ObRag | null>((worst, journey) => {
    const candidate = journey.rag ?? null
    if (candidate == null) return worst
    if (worst == null) return candidate
    return RAG_SEVERITY[candidate] > RAG_SEVERITY[worst] ? candidate : worst
  }, null)

  const holds = journeys.map(journeyHold)
  const hold: JourneyHold = holds.includes(null)
    ? null
    : holds.includes('GATE_LOCKED')
      // The gate first, for `journeyHold`'s own reason: it is the one a reader
      // can go and act on.
      ? 'GATE_LOCKED'
      : 'HELD_BY_SIBLING'

  return {
    product: journeys[0]?.product ?? UNKNOWN_PRODUCT,
    journeys,
    servicesTotal,
    servicesSettled,
    percentComplete: servicesTotal === 0 ? 0 : Math.round((servicesSettled / servicesTotal) * 100),
    rag,
    hold,
    tat: tatUsage({
      totalTatDays: journeys.reduce((sum, j) => sum + (j.totalTatDays ?? 0), 0),
      utilizedHours: journeys.reduce((sum, j) => sum + (j.utilizedHours ?? 0), 0),
    }),
    stepIds: new Set(steps.map((step) => step.id)),
  }
}

/** How many of this client's open escalations sit on a service of this
 * product — the count the card prints, and the reason `stepIds` is on the
 * group at all. */
export function openEscalationCount(
  group: Pick<ObProductGroup, 'stepIds'>,
  openEscalationStepIds: ReadonlySet<number> | undefined,
): number {
  if (!openEscalationStepIds || openEscalationStepIds.size === 0) return 0
  let count = 0
  for (const stepId of openEscalationStepIds) if (group.stepIds.has(stepId)) count += 1
  return count
}
