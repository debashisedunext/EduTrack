/**
 * C-123 · pure arithmetic behind the Module Service catalogue page, kept out
 * of the component that draws it — `journeyStrip.ts`'s own precedent for
 * this codebase.
 */

export interface CatalogueEntry {
  activeTemplateId: number
  dependsOnTemplateId: number | null
}

/**
 * Which of the catalogue's other active templates `templateId` may safely
 * declare a dependency on — the picker's own exclusion list, mirroring
 * `ObJourneyTemplateService#updateDependsOn`'s server-side walk so a reader
 * never picks an option the server refuses.
 *
 * <p>Excludes the template itself, and every template that already depends,
 * directly or transitively, on `templateId` — choosing one of those would
 * close a cycle. The server is still the authority; this only keeps the
 * dropdown from offering an option guaranteed to come back `409`.
 */
export function cycleFreeCandidates<T extends CatalogueEntry>(
  templateId: number,
  catalogue: T[],
): T[] {
  const byId = new Map(catalogue.map((entry) => [entry.activeTemplateId, entry]))

  function dependsOn(candidateId: number, targetId: number): boolean {
    const seen = new Set<number>()
    let cursor = byId.get(candidateId)?.dependsOnTemplateId ?? null
    while (cursor !== null) {
      if (cursor === targetId || seen.has(cursor)) return cursor === targetId
      seen.add(cursor)
      cursor = byId.get(cursor)?.dependsOnTemplateId ?? null
    }
    return false
  }

  return catalogue.filter(
    (entry) => entry.activeTemplateId !== templateId && !dependsOn(entry.activeTemplateId, templateId),
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
