import * as React from 'react'
import { useQueries } from '@tanstack/react-query'

import type { TicketSummary } from '@/api/generated/model/ticketSummary'
import type { TemplateMapping } from '@/api/generated/model/templateMapping'
import type { WorkflowStage } from '@/api/generated/model/workflowStage'
import { useListWorkflowTemplates } from '@/api/generated/masters/masters'
import { buildCompactDots, type CompactDot } from '@/components/ribbon/compactDots'
import { mappingsQueryOptions } from '@/features/masters/templates/templateQueries'
import { buildTemplateRouting, routeToTemplate } from '@/features/masters/templates/routeToTemplate'

/**
 * B-051 · what stage sequence does each row on S-17 belong to, in a number of
 * requests bounded by the workflow master rather than by the page.
 *
 * ## The question this answers, and why it was open until B-041
 *
 * `features/tickets/list/README.md` recorded the compact ribbon as deliberately
 * unbuilt by C-014, with the reason spelled out: a grid mixing projects — which
 * is what Admin and PM see — "cannot resolve one row's dot count without
 * resolving that row's own template, and doing that per row is the
 * per-ticket-detail waterfall S-20 deliberately collapsed into one aggregated
 * call". It then named the two acceptable exits, one of which was **"a
 * stage-order lookup keyed by project and task type that does not require
 * fetching every template"**.
 *
 * B-041 shipped the routing table that makes such a lookup answerable at all.
 * The clause that mattered turned out to be the last one — *without fetching
 * every template*, meaning without a request per row. So this reads the table,
 * not one answer per pair:
 *
 * 1. `GET /masters/workflow-templates` — every template with its stages, and
 *    already fetched by this screen for the stage filter, so the second
 *    subscription costs a cache read and no request.
 * 2. `GET /masters/workflow-templates/{id}/mappings` — one per template. Three
 *    in the seeded fixture; a number an Admin curates by hand thereafter.
 *
 * That is bounded by the master and independent of rows, of paging and of how
 * many projects a reader can see. Asking `.../resolution` per distinct pair was
 * built first and measured worse: the fixture's generator spreads `taskTypeId`
 * over eleven values, so a page of 25 rows was up to 25 requests — the shape
 * the README refused this column over, arriving one step further along.
 *
 * The cost is that `routeToTemplate` is a second implementation of a server
 * rule. Its own header carries why that is affordable and what pins it: a test
 * asserting it agrees with `.../resolution` across every pair in the fixture.
 *
 * ## Both reads are permitted to every role
 *
 * `GET /masters/workflow-templates` and `.../{id}/mappings` are both
 * `everyRole` in the permission matrix, so every role that can open this grid
 * can resolve its rows. Nothing here needed a contract change, a route, a
 * migration or a `PermissionMatrix` entry.
 *
 * ## The version caveat, recorded rather than papered over
 *
 * This resolves the template a pair maps to **today**, and blueprint §7.4 says
 * live tickets keep the template version they started on. Nothing versions a
 * template yet — B-042's note is explicit that "there is no version column to
 * clone into yet" — so today's routing is the only answer anybody can give, and
 * the same one `TicketDetailPage` gets. Where the two genuinely disagree, the
 * ticket's `currentStageCode` will not be in the resolved template's stage list
 * and `buildCompactDots` returns `null`, which the column renders as an em dash
 * rather than as a journey that never happened. When template versioning lands,
 * this hook reads the ticket's own version and the caveat goes away.
 */
export function useTicketStageDots(tickets: TicketSummary[]): Map<string, CompactDot[] | null> {
  // No options, so the key and the observer settings match `TicketListPage`'s
  // own call for the stage filter exactly — two subscriptions to one cache
  // entry and one request, rather than two observers disagreeing about when it
  // is stale.
  const { data: templatesData } = useListWorkflowTemplates()
  const templates = React.useMemo(() => templatesData?.data ?? [], [templatesData])

  /**
   * One request per template, sharing S-13 tab 3's cache entry and its
   * invalidation — an Admin who edits a routing rule sees this grid re-route
   * without a reload, which a private key here would not have given.
   */
  const mappingQueries = useQueries({
    queries: templates.map((template) => mappingsQueryOptions(template.id)),
  })

  /** `templateId` → its stages, in the vocabulary shape the list endpoint
   * serves. Deprecated stages included — see `buildCompactDots`. */
  const stagesByTemplate = React.useMemo(() => {
    const map = new Map<number, WorkflowStage[]>()
    for (const template of templates) map.set(template.id, template.stages ?? [])
    return map
  }, [templates])

  // Built on every render rather than memoised. `useQueries` hands back a fresh
  // array each time, so a `useMemo` over it would need a hand-rolled signature
  // of its contents and an `exhaustive-deps` suppression to go with it — two
  // moving parts to save rebuilding a map of a handful of entries, in a
  // component that already builds `renderContext` fresh each render.
  const mappingsByTemplate = new Map<number, TemplateMapping[]>()
  templates.forEach((template, index) => {
    const mappings = mappingQueries[index]?.data?.mappings
    if (mappings) mappingsByTemplate.set(template.id, mappings)
  })

  /**
   * **Nothing renders until the whole routing table is in hand**, and this is a
   * correctness guard rather than a loading nicety.
   *
   * `routeToTemplate` falls through to the default template when no rule
   * matches — which is right, and is what the server does. But "no rule
   * matched" and "the rules have not arrived yet" are indistinguishable from a
   * half-loaded table, so a grid that drew dots early put **every** row on
   * Standard Dev Flow's eight stages for a frame or two: a wrong ribbon on
   * most of them, and a claim nothing could back. It was visible in
   * `TicketJourneyColumn.test.tsx`, which asserted on the first paint and saw
   * one template where two were routed.
   *
   * `isSuccess` and not `!isPending`, so a failed read is also a held column
   * rather than a silent fall-through to the default. Either way the cell
   * renders an em dash — **a missing column, never a broken grid**; the dots
   * are a scanning aid and the row's Status column still says where the ticket
   * is.
   */
  const routingReady =
    templates.length > 0 && mappingQueries.every((query) => query.isSuccess)

  const dots = new Map<string, CompactDot[] | null>()
  if (!routingReady) return dots

  const routing = buildTemplateRouting(templates, mappingsByTemplate)
  for (const ticket of tickets) {
    if (!ticket.ticketId) continue
    const templateId = routeToTemplate(routing, ticket.project?.id ?? null, ticket.taskTypeId ?? null)
    const stages = templateId != null ? stagesByTemplate.get(templateId) : undefined
    dots.set(ticket.ticketId, stages ? buildCompactDots(stages, ticket) : null)
  }
  return dots
}
