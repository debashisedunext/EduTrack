import type { ObProjectStage } from '@/api/generated/model/obProjectStage'

/**
 * The one thing the Step ribbon needs from the roll-up: a stable order.
 *
 * <h2>What used to live here, and where it went</h2>
 *
 * <p>This file held the ribbon's whole vocabulary — state, settled count,
 * fraction, count label, default selection — all read off `ObProjectStage`, the
 * per-service roll-up the server computes. Every one of those now has a scoped
 * twin in `moduleStripStats.ts`, derived from the tree node instead, and the
 * two cannot both be right: the roll-up counts the service, the node counts
 * whoever is reading it. A Step that says "2/4 tasks" from the roll-up while
 * the strip above it says "of your 1 task" is the disagreement the move exists
 * to prevent, so the unscoped versions were removed rather than left as an
 * alternative somebody could reach for.
 *
 * <p>Ordering survives because it is genuinely not a scoped question. Sequence
 * is the template's, whoever is looking.
 */

/**
 * Ribbon order: by the master's sequence, then by key.
 *
 * The server already returns them this way, so this is a guard rather than a
 * transformation — a ribbon whose stops reorder because a response arrived in a
 * different order would be a genuinely confusing bug, and sorting a
 * seven-element array to rule it out costs nothing.
 *
 * `stageKey` breaks ties because the Ungrouped bucket carries sequence 9999 and
 * several of them can exist — one per stage group with no implementation stage.
 */
export function orderStages(stages: readonly ObProjectStage[]): ObProjectStage[] {
  return [...stages].sort((a, b) => a.sequence - b.sequence || a.stageKey - b.stageKey)
}
