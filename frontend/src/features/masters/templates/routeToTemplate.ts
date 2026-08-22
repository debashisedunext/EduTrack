import type { TemplateMapping } from '@/api/generated/model/templateMapping'
import type { WorkflowTemplate } from '@/api/generated/model/workflowTemplate'

/**
 * B-051 · §4A.9's routing ladder, answered from the rules already in hand
 * rather than one request per pair.
 *
 * ## Why this exists when `GET .../resolution` already answers the question
 *
 * It is the right route for S-13 tab 3, which asks about **one** pair a person
 * picked, and `useTemplateResolution` is what that screen uses. S-17 asks about
 * every pair on a page at once, and there the per-pair shape does not hold: the
 * grid mixes projects and task types, so a page of 25 rows is up to 25 distinct
 * pairs and 25 requests — measurably so against the fixture, whose generator
 * spreads `taskTypeId` over eleven values. That is the per-row waterfall
 * `features/tickets/list/README.md` refused this column over in the first
 * place, arriving one step further along.
 *
 * The routing *table*, by contrast, is bounded by the master and not by the
 * page: one `listWorkflowTemplates` the ticket list already makes, plus one
 * `mappings` call per template. Three templates in the seeded fixture, and a
 * number an Admin curates by hand thereafter. It does not grow with rows, with
 * paging, or with how many projects a PM can see.
 *
 * ## This is a second implementation of a server rule, and that is the cost
 *
 * `TemplateResolver.java` and the mock's `resolveTemplate` are the other two.
 * A third copy that drifts would render the wrong ribbon on the wrong ticket
 * and say nothing, which is the failure mode this codebase most often refuses
 * a client-side copy over.
 *
 * Two things make it affordable here. The server hands over its own ranking
 * key — `TemplateMapping.specificity` is documented as "what the resolver
 * breaks ties on (project before task type at 1)" — so the ordering is read
 * rather than re-derived. And `routeToTemplate.test.ts` asserts this function
 * agrees with `GET /masters/workflow-templates/resolution` across **every**
 * project × task type pair in the fixture, so a drift is a failing test rather
 * than a wrong dot.
 *
 * If a resolution endpoint that takes many pairs at once ever lands, this
 * function goes and the ticket list calls it instead.
 */

/** Everything the ladder needs, assembled from the two reads above. */
export interface TemplateRouting {
  /** Every rule, from every template, flattened — the table the server holds. */
  mappings: (TemplateMapping & { templateId: number })[]
  /** Exactly one template is the default (B-004). Null if none is active. */
  defaultTemplateId: number | null
}

/**
 * `specificity` in reverse — 0 is the narrowest rung, matching the server's own
 * comparator and the mock's `rank`.
 *
 * A project beats a task type at the half-wildcard rung because **a project is
 * the narrower population**: the server writes the same comparator and the same
 * sentence.
 */
function rung(mapping: { projectId?: number | null; taskTypeId?: number | null }): number {
  const hasProject = mapping.projectId != null
  const hasTaskType = mapping.taskTypeId != null
  if (hasProject && hasTaskType) return 0
  if (hasProject) return 1
  if (hasTaskType) return 2
  return 3
}

/** The rules and the default, from the two payloads the ticket list holds. */
export function buildTemplateRouting(
  templates: WorkflowTemplate[],
  mappingsByTemplate: Map<number, TemplateMapping[]>,
): TemplateRouting {
  const mappings = templates.flatMap((template) =>
    (mappingsByTemplate.get(template.id) ?? []).map((mapping) => ({
      ...mapping,
      templateId: template.id,
    })),
  )

  // Active as well as default: `resolveTemplate` falls back to a template that
  // is both, and a deactivated default routes nothing.
  const fallback = templates
    .filter((template) => template.isDefault && template.isActive)
    .sort((a, b) => a.id - b.id)[0]

  return { mappings, defaultTemplateId: fallback?.id ?? null }
}

/**
 * Which template a ticket raised on this pair would use, or `null` when
 * nothing matched and no template is the default.
 *
 * `null` on both halves is a question rather than a missing value — "what does
 * a project with no rule of its own resolve to?" — which is the resolution
 * route's own wording for its two optional params.
 *
 * The `NONE` rung is a state nothing in the schema forbids, and `null` is the
 * honest answer to it. `buildCompactDots` turns that into an em dash rather
 * than into a hard-coded fallback ribbon.
 */
export function routeToTemplate(
  routing: TemplateRouting,
  projectId: number | null,
  taskTypeId: number | null,
): number | null {
  const winner = routing.mappings
    .filter(
      (mapping) =>
        (mapping.projectId == null || mapping.projectId === projectId) &&
        (mapping.taskTypeId == null || mapping.taskTypeId === taskTypeId),
    )
    // Lowest rung wins; the lowest id breaks a tie, exactly as the server does.
    .sort((a, b) => rung(a) - rung(b) || a.id - b.id)[0]

  return winner?.templateId ?? routing.defaultTemplateId
}
