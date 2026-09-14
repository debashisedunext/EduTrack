import type { ObJourneyTemplateSummary } from '@/api/generated/model/obJourneyTemplateSummary'

/**
 * The two pure decisions behind the New Project form's service picker.
 */

/**
 * The services a client can actually be boarded onto: the **active** version of
 * each named service of this product, in catalogue order.
 *
 * <h2>Why filtering on `isActive` is not enough on its own</h2>
 *
 * `listObJourneyTemplates` returns every version of every service, because the
 * OB-07 catalogue draws version history. `isActive` is true for at most one
 * version per service, so filtering on it is correct — but a service that has
 * *never* been published has no active version at all, only drafts. Those are
 * excluded rather than offered: instantiating a journey from a draft would pin
 * a client to a template the admin is still editing, and the server refuses it
 * anyway. A product whose services are all drafts renders the "publish one
 * first" message, which is the actionable fact.
 *
 * <h2>The productId guard</h2>
 *
 * The list is fetched with `?productId=`, so every row should already belong to
 * it. The filter is kept because a stale query result can arrive after the
 * product has been changed — React Query serves the previous product's data for
 * one render while the new request is in flight — and a picker that briefly
 * lists another product's services is one somebody can check a row in.
 */
export function activeServicesOf(
  templates: ObJourneyTemplateSummary[],
  productId: number | null,
): ObJourneyTemplateSummary[] {
  if (productId == null) return []
  return templates
    .filter((t) => t.productId === productId && t.isActive)
    .slice()
    .sort((a, b) => a.sequence - b.sequence || a.id - b.id)
}

/**
 * The Category cell: the implementation stages this service covers, in the
 * designer's own order.
 *
 * <h2>Deduplicated by name, not by id</h2>
 *
 * A stage group carries a name copied from the OB-15 master at creation, so two
 * groups can legitimately read "Configuration" while holding different ids —
 * the copy is what stops a rename next year re-labelling a template published
 * this year. For a reader scanning a Category column, two identical chips are
 * noise, so the names are folded. The ids still matter to the server's roll-up,
 * which is why the project's own stage figures are computed there and not here.
 *
 * <h2>"Ungrouped" is shown rather than hidden</h2>
 *
 * It is a real bucket — tasks written before the stage master existed — and a
 * service carrying one is a service somebody should tidy in the designer.
 * Hiding it would make the Category column quietly disagree with the task list
 * underneath it.
 */
export function categoryOf(service: ObJourneyTemplateSummary): string[] {
  const stages = service.stages ?? []
  const seen = new Set<string>()
  const names: string[] = []
  for (const stage of stages) {
    if (seen.has(stage.name)) continue
    seen.add(stage.name)
    names.push(stage.name)
  }
  return names
}
