import type { JourneyRow } from '@/api/generated/model/journeyRow'

export interface ResourceRollup {
  id: number
  displayName: string
  role: string | null
  stages: number
  iterations: number
  elapsedMins: number
  effortHrs: number
}

/**
 * C-057 · §4A.4's "ROLL-UP BY RESOURCE" band, derived from the hops rather than
 * fetched.
 *
 * <p>`GET /journey` does return `perResource`, but only `{resource, effortHrs}`
 * — and §4A.4's band shows four more things per person: how many stages they
 * held, how many iterations they were involved in, and their elapsed time.
 * Every one of those is a fact about the rows already in hand for this cycle,
 * so deriving them here costs nothing and asking the server for them would mean
 * a contract change, a client regeneration and a second shape to keep in step.
 *
 * <p><b>Effort still comes from the server's figures where the server has
 * them.</b> `cycleTotalHrs` and `allCyclesTotalHrs` are read straight off the
 * payload rather than summed here, on `EffortTab`'s own precedent: a total
 * summed from whatever the client happens to hold will disagree with the panel
 * beside it the moment the two are fetched differently. Only the per-person
 * effort is added up locally, and only because it is a partition of rows this
 * component already has in full.
 */
export function rollupByResource(rows: JourneyRow[]): ResourceRollup[] {
  const byId = new Map<number, ResourceRollup & { stageCodes: Set<string>; iterationNos: Set<number> }>()

  for (const row of rows) {
    const resource = row.resource
    // An unassigned hop (§4A.2's project-level queue) belongs to nobody, so it
    // contributes to the cycle's elapsed time but to no person's row. Inventing
    // an "Unassigned" pseudo-resource here would put a queue in the same list
    // as the people, which is not what the band is for.
    if (!resource) continue

    const existing = byId.get(resource.id)
    const entry = existing ?? {
      id: resource.id,
      displayName: resource.displayName,
      role: row.role ?? null,
      stages: 0,
      iterations: 0,
      elapsedMins: 0,
      effortHrs: 0,
      stageCodes: new Set<string>(),
      iterationNos: new Set<number>(),
    }

    if (row.stageCode) entry.stageCodes.add(row.stageCode)
    if (row.iterationNo != null) entry.iterationNos.add(row.iterationNo)
    entry.elapsedMins += row.durationMins ?? 0
    entry.effortHrs += row.effortHrs ?? 0

    byId.set(resource.id, entry)
  }

  return [...byId.values()]
    .map((e) => ({
      id: e.id,
      displayName: e.displayName,
      role: e.role,
      stages: e.stageCodes.size,
      iterations: e.iterationNos.size,
      elapsedMins: e.elapsedMins,
      effortHrs: round2(e.effortHrs),
    }))
    .sort((a, b) => b.effortHrs - a.effortHrs || a.displayName.localeCompare(b.displayName))
}

/**
 * The cycle's elapsed time — the sum of its hops' durations.
 *
 * <p>Deliberately **not** wall-clock from first entry to last exit. A ticket
 * can sit outside any stage between a close and a reopen, and counting that gap
 * would make "elapsed" a different measure from the column above it, which is
 * the one thing a totals row must not be.
 *
 * <p>An open hop contributes nothing, for the same reason it shows an em dash:
 * it has no measured duration yet.
 */
export function cycleElapsedMins(rows: JourneyRow[]): number {
  return rows.reduce((sum, row) => sum + (row.durationMins ?? 0), 0)
}

function round2(n: number): number {
  return Math.round(n * 100) / 100
}
