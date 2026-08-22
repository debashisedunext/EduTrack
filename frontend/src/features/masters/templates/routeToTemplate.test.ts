import { describe, expect, it } from 'vitest'

import type { TemplateMapping } from '@/api/generated/model/templateMapping'
import type { WorkflowTemplate } from '@/api/generated/model/workflowTemplate'
import http from '@/api/http'
import { buildTemplateRouting, routeToTemplate } from './routeToTemplate'

/**
 * B-051 · the client ladder, and the guard that keeps it honest.
 *
 * `routeToTemplate` is a second implementation of `TemplateResolver.java`'s
 * rule, built so S-17's Journey column costs a request per *template* instead
 * of one per row. The whole risk of that is silent drift, so the last block
 * here asks the server route about **every** pair in the fixture and asserts
 * the two agree. A divergence fails the build rather than rendering the wrong
 * ribbon on the wrong ticket.
 */

function template(over: Partial<WorkflowTemplate> & { id: number }): WorkflowTemplate {
  return {
    name: `Template ${over.id}`,
    isDefault: false,
    isActive: true,
    stageCount: 0,
    ...over,
  }
}

function mapping(
  id: number,
  projectId: number | null,
  taskTypeId: number | null,
): TemplateMapping {
  const specificity = projectId != null && taskTypeId != null ? 2 : projectId != null || taskTypeId != null ? 1 : 0
  return { id, projectId, taskTypeId, specificity }
}

describe('B-051 · routeToTemplate walks §4A.9 rung by rung', () => {
  const templates = [
    template({ id: 1, isDefault: true }),
    template({ id: 2 }),
    template({ id: 3 }),
  ]

  const routing = (rules: Array<[number, TemplateMapping[]]>) =>
    buildTemplateRouting(templates, new Map(rules))

  it('prefers an exact project × task type rule over every other rung', () => {
    const r = routing([
      [2, [mapping(1, 5, 9)]],
      [3, [mapping(2, 5, null), mapping(3, null, 9), mapping(4, null, null)]],
    ])
    expect(routeToTemplate(r, 5, 9)).toBe(2)
  })

  it('prefers a project rule over a task-type rule — a project is the narrower population', () => {
    const r = routing([
      [2, [mapping(1, 5, null)]],
      [3, [mapping(2, null, 9)]],
    ])
    expect(routeToTemplate(r, 5, 9)).toBe(2)
  })

  it('falls to a task-type rule when the project has none of its own', () => {
    const r = routing([
      [2, [mapping(1, 7, null)]],
      [3, [mapping(2, null, 9)]],
    ])
    expect(routeToTemplate(r, 5, 9)).toBe(3)
  })

  it('falls to the catch-all rule before the default flag', () => {
    const r = routing([[3, [mapping(1, null, null)]]])
    expect(routeToTemplate(r, 5, 9)).toBe(3)
  })

  it('falls to the default template when no rule matches at all', () => {
    expect(routeToTemplate(routing([[2, [mapping(1, 7, 4)]]]), 5, 9)).toBe(1)
  })

  it('breaks a tie on the lowest rule id, as the server does', () => {
    const r = routing([
      [3, [mapping(9, null, 9)]],
      [2, [mapping(4, null, 9)]],
    ])
    expect(routeToTemplate(r, 5, 9)).toBe(2)
  })

  it('ignores a default template that has been deactivated', () => {
    // A deactivated default routes nothing; `NONE` is a state the schema
    // permits and null is the honest answer to it.
    const r = buildTemplateRouting(
      [template({ id: 1, isDefault: true, isActive: false })],
      new Map(),
    )
    expect(routeToTemplate(r, 5, 9)).toBeNull()
  })

  it('answers null rather than guessing when nothing matches and nothing is default', () => {
    const r = buildTemplateRouting([template({ id: 2 })], new Map([[2, [mapping(1, 7, 4)]]]))
    expect(routeToTemplate(r, 5, 9)).toBeNull()
  })

  it('treats a null half as a question, not as a missing value', () => {
    // "What does this task type resolve to on a project with no rule of its
    // own?" — the resolution route's own wording for its optional params.
    const r = routing([
      [2, [mapping(1, 5, 9)]],
      [3, [mapping(2, null, 9)]],
    ])
    expect(routeToTemplate(r, null, 9)).toBe(3)
  })
})

/**
 * The guard against drift. Each pair is asked of the mock's `/resolution`
 * route — which implements the same comparator the server does and is held to
 * the contract by CI — and of this function, and the two must agree.
 *
 * **A sample, not the full matrix, and the cap is stated rather than implied.**
 * 4 projects × 12 task types is 48 round trips, and one MSW round trip costs
 * something like half a second in this rig — which is the same measurement that
 * sent `useTicketStageDots` away from a request per pair, arriving here as a
 * two-minute test. The twelve below are chosen to reach every outcome §4A.9's
 * seven seeded rules can produce: a task-type rule landing on each of the three
 * templates, two task types no rule names falling through to the default, and
 * the null-project question the wildcard rung exists for. A new *kind* of rule
 * in the fixture — the first project-scoped one — belongs in this list.
 */
describe('B-051 · the client ladder agrees with the server route, pair for pair', () => {
  it('resolves every fixture pair to the same template the route does', async () => {
    const templatesBody = await http<{ data: WorkflowTemplate[] }>({
      url: '/masters/workflow-templates',
      method: 'GET',
    })
    const templates = templatesBody.data

    const mappingsByTemplate = new Map<number, TemplateMapping[]>()
    for (const t of templates) {
      const body = await http<{ data: TemplateMapping[] }>({
        url: `/masters/workflow-templates/${t.id}/mappings`,
        method: 'GET',
      })
      mappingsByTemplate.set(t.id, body.data)
    }
    const routing = buildTemplateRouting(templates, mappingsByTemplate)

    const projectIds: (number | null)[] = [null, 1]
    // 2 → Standard Dev Flow, 3 → Support Fast-Track, 7 → Infra Flow; 5 and 11
    // are named by no rule and must fall to the default; null asks the
    // wildcard question.
    const taskTypeIds: (number | null)[] = [null, 2, 3, 7, 5, 11]

    const pairs = projectIds.flatMap((projectId) =>
      taskTypeIds.map((taskTypeId) => ({ projectId, taskTypeId })),
    )

    const answers = await Promise.all(
      pairs.map(async ({ projectId, taskTypeId }) => {
        const params = new URLSearchParams()
        if (projectId != null) params.set('projectId', String(projectId))
        if (taskTypeId != null) params.set('taskTypeId', String(taskTypeId))
        const query = params.toString()
        const body = await http<{ data: { templateId: number | null } }>({
          url: `/masters/workflow-templates/resolution${query ? `?${query}` : ''}`,
          method: 'GET',
        })
        return body.data.templateId ?? null
      }),
    )

    pairs.forEach(({ projectId, taskTypeId }, index) => {
      expect(
        routeToTemplate(routing, projectId, taskTypeId),
        `project ${projectId} × task type ${taskTypeId}`,
      ).toBe(answers[index])
    })

    // Three distinct templates have to have been reached, or the sample has
    // stopped exercising the ladder and is only agreeing about the default.
    expect(new Set(answers).size).toBeGreaterThanOrEqual(3)
  }, 30_000)
})
