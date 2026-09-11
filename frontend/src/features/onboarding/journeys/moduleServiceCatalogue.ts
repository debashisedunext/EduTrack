/**
 * C-123 · pure arithmetic behind the Module Service catalogue page, kept out
 * of the component that draws it — `journeyStrip.ts`'s own precedent for
 * this codebase.
 */

export interface CatalogueEntry {
  activeTemplateId: number
  /** Every service this one waits behind — a set, not one id. Ascending, empty when unheld. */
  dependsOnTemplateIds: number[]
}

/**
 * Which of the catalogue's other active templates `templateId` may safely
 * declare a dependency on — the picker's own exclusion list, mirroring
 * `ObJourneyTemplateService#updateDependsOn`'s server-side walk so a reader
 * never picks an option the server refuses.
 *
 * Excludes the template itself, and every template that already depends,
 * directly or transitively, on `templateId` — choosing one of those would
 * close a cycle. The server is still the authority; this only keeps the
 * picker from offering an option guaranteed to come back `409`.
 *
 * **A search, not a walk.** This followed a single `dependsOnTemplateId`
 * cursor until the picker became multi-select. With a set per node the
 * catalogue is a directed graph, so `reaches` is a breadth-first search over
 * every outgoing edge — a template three hops away down the second branch of
 * a fork is exactly the cycle a chain walk misses, and it misses it by
 * reporting the option as safe. `ObJourneyTemplateService#requireNoCycle` was
 * changed in the same shape and for the same reason; these two have to agree
 * or the picker offers options the server refuses.
 *
 * `seen` is what makes this terminate on a catalogue that is already cyclic.
 * It should not be — the server refuses to write one — but a dropdown that
 * hangs the browser is a worse answer than one that lists a stale option.
 */
export function cycleFreeCandidates<T extends CatalogueEntry>(
  templateId: number,
  catalogue: T[],
): T[] {
  const byId = new Map(catalogue.map((entry) => [entry.activeTemplateId, entry]))

  function reaches(candidateId: number, targetId: number): boolean {
    const seen = new Set<number>([candidateId])
    const frontier = [candidateId]
    while (frontier.length > 0) {
      const current = frontier.shift()!
      for (const next of byId.get(current)?.dependsOnTemplateIds ?? []) {
        if (next === targetId) return true
        if (!seen.has(next)) {
          seen.add(next)
          frontier.push(next)
        }
      }
    }
    return false
  }

  return catalogue.filter(
    (entry) => entry.activeTemplateId !== templateId && !reaches(entry.activeTemplateId, templateId),
  )
}

/** `move`'s own up/down step, applied to a plain array of template ids — the reorder request's exact shape. */
export function moveTemplate(orderedIds: number[], from: number, to: number): number[] {
  if (from === to || from < 0 || to < 0 || from >= orderedIds.length || to >= orderedIds.length) {
    return orderedIds
  }
  const next = [...orderedIds]
  const [moved] = next.splice(from, 1)
  next.splice(to, 0, moved)
  return next
}

/** One step, as much of it as the catalogue's two derived columns read. */
export interface StepOwnership {
  sequence: number
  ownerUserId?: number | null
  ownerRole?: string | null
  items?: readonly unknown[]
}

/**
 * How many checklist items a Module Service carries, across every step.
 *
 * Not on `ObJourneyTemplateSummary` — items nest inside the steps of the
 * *detail* read — so the catalogue totals them from the detail it already
 * fetches per row. The alternative is a `checklistItemCount` field on the list
 * row, which is the better long-run answer and a contract change.
 */
export function checklistCount(steps: readonly StepOwnership[]): number {
  return steps.reduce((total, step) => total + (step.items?.length ?? 0), 0)
}

/**
 * The catalogue's "Default Implementor" column.
 *
 * ## A Module Service has no implementor of its own
 *
 * Ownership is a fact about a *step*: `ObJourneyTemplateStep` carries
 * `ownerUserId`, and `ownerRole` where no particular person is pinned. So this
 * column is derived, and the reading it takes is **the first step's owner** —
 * who the service actually lands on when a client is boarded. The alternative
 * reading, whoever owns the most steps, answers "who mostly runs this", which
 * is a different question and not the one a catalogue is scanned for.
 *
 * The fallback chain is the same one the ribbon reads: a named person, else
 * the role that covers the step, else nobody yet. `extra` counts the *other*
 * distinct owners further down the service, so a journey run by four people
 * does not read as one person's — a column that named only the first would
 * quietly under-report every shared service.
 */
export function defaultImplementor(
  steps: readonly StepOwnership[],
  users: readonly { id: number; displayName: string }[],
): { label: string; extra: string; hint: string; named: boolean } {
  if (steps.length === 0) {
    return {
      label: 'No steps yet',
      extra: '',
      hint: 'This service has no steps, so nothing on it names an owner.',
      named: false,
    }
  }

  const ordered = [...steps].sort((a, b) => a.sequence - b.sequence)
  const first = ordered[0]
  const others = new Set(
    ordered
      .map((step) => step.ownerUserId)
      .filter((id): id is number => id != null && id !== first.ownerUserId),
  )
  const extra = others.size === 0 ? '' : `+${others.size} other${others.size === 1 ? '' : 's'}`
  const spread = extra === '' ? '' : ` ${others.size} other step owner${others.size === 1 ? '' : 's'} follow.`

  if (first.ownerUserId != null) {
    const user = users.find((u) => u.id === first.ownerUserId)
    return {
      label: user?.displayName ?? `user #${first.ownerUserId}`,
      extra,
      hint: `Owner of this service's first step — who it lands on when a client is boarded.${spread}`,
      named: true,
    }
  }
  if (first.ownerRole) {
    return {
      label: `Role · ${first.ownerRole}`,
      extra,
      hint: `The first step names no person, only the role that covers it — whoever holds ${first.ownerRole} picks this service up.${spread}`,
      named: false,
    }
  }
  return {
    label: 'Unassigned',
    extra,
    hint: `The first step names neither an owner nor a role, so nobody is lined up to start this service.${spread}`,
    named: false,
  }
}
